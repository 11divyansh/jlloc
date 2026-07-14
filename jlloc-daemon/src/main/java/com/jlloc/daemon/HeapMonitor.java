package com.jlloc.daemon;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The daemon's continuous heap-monitoring loop.
 *
 * Every POLL_INTERVAL_SECONDS it:
 *   1. Iterates every known JVM in ProcessRepository
 *   2. Skips any without JMX capability (already probed by JmxConnector)
 *   3. Reads a fresh HeapSample via JmxConnector
 *   4. Adds it to that PID's HeapTimeline (in-memory history)
 *   5. Runs DiagnosisEngine on the updated timeline
 *   6. Writes the verdict back to ProcessRepository
 */
public class HeapMonitor {

    private static final long POLL_INTERVAL_SECONDS = 5;

    private final ProcessRepository repository;
    private final DiagnosisEngine diagnosisEngine;
    private final MemorySignalExtractor signalExtractor;

    private final Map<Long, HeapTimeline> timelines = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jlloc-heap-monitor");
        t.setDaemon(true);
        return t;
    });

    // Optional alert callback, wired up later by DaemonMain so the
    // monitor can notify the CLI/socket layer when something transitions
    // to CRITICAL or LEAK_SUSPECTED without the caller having to poll
    private Consumer<AlertEvent> onAlert = event -> {};

    public HeapMonitor(ProcessRepository repository) {
        this(repository, new DiagnosisEngine(), new MemorySignalExtractor());
    }

    public HeapMonitor(ProcessRepository repository, DiagnosisEngine diagnosisEngine,
                       MemorySignalExtractor signalExtractor) {
        this.repository = repository;
        this.diagnosisEngine = diagnosisEngine;
        this.signalExtractor = signalExtractor;
    }

    public HeapMonitor onAlert(Consumer<AlertEvent> callback) {
        this.onAlert = callback;
        return this;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::pollAll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void forgetPid(long pid) {
        timelines.remove(pid);
        signalExtractor.forgetPid(pid);
    }

    /**
     * One full poll cycle across all known JVMs
     */
    void pollAll() {
        // Refresh system-wide OS signals (swap rate, I/O wait) exactly
        // once for this cycle must happen before any pollOne() call,
        // since those consume the cached result rather than re-reading
        // /proc/vmstat per PID (see OsMemorySignalExtractor javadoc).
        signalExtractor.beginCycle();
        for (ProcessRepository.ProcessRecord record : repository.all()) {
            try {
                pollOne(record);
            } catch (Exception e) {
                // One JVM failing to poll (exited, permissions changed,
                // etc.) must not kill the entire poll cycle for the
                // others. Log and continue.
                System.err.printf("[jlloc-heap-monitor] poll failed for PID %d: %s%n",
                        record.pid(), e.getMessage());
            }
        }
    }

    private void pollOne(ProcessRepository.ProcessRecord record) throws Exception {
        JvmCapabilities caps = record.capabilities();

        // Skip JVMs we already know we can't reach - no point trying
        // a JMX connection we know is going to fail
        if (caps == null || !caps.jmxReachable()) {
            return;
        }

        try (JmxConnector jmx = new JmxConnector(record.pid())) {
            jmx.connect();
            JmxConnector.HeapStats stats = jmx.readHeapStats();

            HeapSample sample = HeapSample.from(stats, Instant.now());

            HeapTimeline timeline = timelines.computeIfAbsent(record.pid(), pid -> new HeapTimeline());
            timeline.add(sample);

            repository.updateHeapStats(record.pid(), stats);

            MemorySignal signal = signalExtractor.extract(timeline, jmx, record.pid());
            DiagnosisResult diagnosis = diagnosisEngine.diagnose(signal);

            DiagnosisResult previous = record.diagnosis();
            repository.updateDiagnosis(record.pid(), diagnosis);
            repository.updateLastSignal(record.pid(), signal);

            // Fire alert only on severity worsening, not on every poll,
            // and not for diagnosis-only changes while severity stays NORMAL
            if (isWorseSeverity(diagnosis.severity(),
                previous == null ? null : previous.severity())) {
                onAlert.accept(new AlertEvent(record, diagnosis));
            }
        }
    }

    private static boolean isWorseSeverity(
            DiagnosisResult.Severity current,
            DiagnosisResult.Severity previous) {
        if (previous == null) return false;
        return severityLevel(current) > severityLevel(previous);
    }

    private static int severityLevel(DiagnosisResult.Severity s) {
        return switch (s) {
            case NORMAL   -> 0;
            case WARNING  -> 1;
            case CRITICAL -> 2;
        };
    }

    public record AlertEvent(
            ProcessRepository.ProcessRecord record,
            DiagnosisResult diagnosis
    ) {
    }
}