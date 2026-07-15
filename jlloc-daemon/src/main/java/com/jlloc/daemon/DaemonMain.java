package com.jlloc.daemon;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The daemon's real entry point.
 *
 * Threading model:
 *   - jlloc-process-watcher  (JvmProcessWatcher)  detects PIDs
 *   - jlloc-heap-monitor     (HeapMonitor)         polls heap stats
 *   - jlloc-status-printer   (this class)          prints status
 *
 */
public class DaemonMain {

    // Single lock for all stdout access
    private static final Object STDOUT_LOCK = new Object();

    public static void main(String[] args) throws InterruptedException, IOException {
        out("[jlloc-daemon] starting up...");

        ProcessRepository repository = new ProcessRepository();
        ProcessFingerprinter fingerprinter = new ProcessFingerprinter();
        JvmProcessWatcher watcher = new JvmProcessWatcher();
        HeapMonitor heapMonitor = new HeapMonitor(repository);
        boolean debugMode = "true".equalsIgnoreCase(System.getenv("JLLOC_DEBUG"));

        DaemonSocketServer socketServer = new DaemonSocketServer(repository);
        socketServer.start();
        out("[jlloc-daemon] socket server listening on port " + socketServer.getPort());

        heapMonitor.onAlert(event -> printAlert(event.record(), event.diagnosis()));

        watcher.onJvmStarted(jvm -> {
            repository.register(jvm);

            ProcessFingerprinter.Classification classification = fingerprinter.classifyDeep(jvm);

            if ("jlloc-self".equals(classification.category())) {
                repository.remove(jvm.pid());
                return;
            }

            repository.updateClassification(jvm.pid(), classification);

            JvmCapabilities capabilities = JmxConnector.probeCapabilities(jvm.pid());
            repository.updateCapabilities(jvm.pid(), capabilities);

            repository.get(jvm.pid()).ifPresent(r -> printStarted(r));
        });

        watcher.onJvmStopped(jvm -> {
            out("[-] PID " + jvm.pid() + " stopped");
            heapMonitor.forgetPid(jvm.pid());
            repository.remove(jvm.pid());
        });

        watcher.start();
        heapMonitor.start();

        ScheduledExecutorService statusScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jlloc-status-printer");
            t.setDaemon(true);
            return t;
        });

        statusScheduler.scheduleAtFixedRate(
                () -> printStatus(repository, debugMode),
                30, 30, TimeUnit.SECONDS);

        out("[jlloc-daemon] watching for JVMs (Ctrl+C to exit)");
        Thread.currentThread().join();
    }

    private static void printStarted(ProcessRepository.ProcessRecord record) {
        String category = record.classification() != null
                ? record.classification().category() : "?";
        String appName = record.classification() != null
                ? record.classification().appName() : "?";
        int weight = record.classification() != null
                ? record.classification().priorityWeight() : 0;

        String capNote = "";
        if (record.capabilities() != null
                && record.capabilities().unavailableReason() != null) {
            capNote = "  [monitoring limited: "
                    + record.capabilities().unavailableReason() + "]";
        }

        out(String.format("[+] PID %-8d %-16s %-24s weight=%d%s",
                record.pid(), category, appName, weight, capNote));
    }

    private static void printAlert(
            ProcessRepository.ProcessRecord record,
            DiagnosisResult d) {

        String appName = record.classification() != null
                ? record.classification().appName() : "unknown";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n[!] %s  PID %d  %s%n",
                d.severity(), record.pid(), appName));
        sb.append(String.format("    Severity:  %s%n", d.severity()));
        sb.append(String.format("    Diagnosis: %s%n", d.diagnosis()));
        if (d.reason() != null) {
            for (String line : d.reason().split("\n")) {
                sb.append("    ").append(line).append("\n");
            }
        }
        if (d.recommendationId() != null) {
            sb.append("    ---\n");
            sb.append("    → ").append(d.recommendationId().shortDescription()).append("\n");
            sb.append("    (run `jlloc explain ").append(appName).append("` for full details)\n");
        }
        out(sb.toString());
    }

    private static void printStatus(ProcessRepository repository, boolean debugMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- jlloc status ---\n");

        for (ProcessRepository.ProcessRecord r : repository.all()) {
            String app = r.classification() != null
                    ? r.classification().appName() : "unknown";

            // "collecting" = diagnosis is null OR the reason explicitly
            // says we're still gathering samples
            boolean stillCollecting = r.diagnosis() == null
                    || (r.diagnosis().reason() != null
                    && r.diagnosis().reason().startsWith("collecting"));

            if (stillCollecting) {
                sb.append(String.format("  %-32s  collecting...%n", app));
                continue;
            }

            DiagnosisResult d = r.diagnosis();

            // Concise header line — always shown
            sb.append(String.format("  %-32s  %s / %s%n",
                    app, d.severity(), d.diagnosis()));

            boolean showDetail = debugMode
                    || d.severity() != DiagnosisResult.Severity.NORMAL;

            if (showDetail) {
                if (d.reason() != null) {
                    for (String line : d.reason().split("\n")) {
                        sb.append("    ").append(line).append("\n");
                    }
                }
                if (d.recommendationId() != null) {
                    sb.append("    ---\n");
                    sb.append("    → ").append(d.recommendationId().shortDescription()).append("\n");
                }
            } else {
                // NORMAL: one compact data line
                String heapStr = r.heapStats() != null
                        ? String.format("%.1f%%",
                        r.heapStats().usedBytes() * 100.0
                                / Math.max(1, r.heapStats().maxBytes()))
                        : "?";
                int gcSignal = d.signalStrengths() != null
                        ? d.signalStrengths().gcPressureSignal() : 0;
                sb.append(String.format("    Heap: %-8s  GC pressure: %d/100%n",
                        heapStr, gcSignal));
            }
        }

        out(sb.toString());
    }

    /**
     * Single point of stdout access. Builds the entire output block
     * as a String first, then prints it in one synchronized write.
     * This prevents interleaving between the watcher, monitor, and
     * status-printer threads which all run concurrently.
     */
    private static void out(String message) {
        synchronized (STDOUT_LOCK) {
            System.out.println(message);
            System.out.flush();
        }
    }
}
