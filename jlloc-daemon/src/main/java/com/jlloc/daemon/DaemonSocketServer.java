package com.jlloc.daemon;

import com.jlloc.common.protocol.Command;
import com.jlloc.common.protocol.ProcessSummary;
import com.jlloc.common.protocol.Response;
import com.jlloc.common.protocol.*;
import oshi.SystemInfo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The daemon's socket server. Listens for CLI connections, reads a
 * Command, dispatches to the appropriate handler, writes a Response.
 *
 * Protocol: one connection = one request/response pair.
 * Client connects → sends one Command (Java serialization) →
 * server sends one Response (Java serialization) → connection closes.
 * Simple, stateless, no multiplexing needed for a CLI tool.
 *
 * The daemon never formats output for a terminal. It returns data
 * records. The CLI formatter decides how to present them.
 */
public class DaemonSocketServer {

    static final int DEFAULT_PORT = 7891;
    static final Path PORT_FILE = Path.of(System.getProperty("user.home"), ".jlloc", "daemon.port");

    private final ProcessRepository repository;
    private final ServerSocket serverSocket;
    private final ExecutorService executor;

    public DaemonSocketServer(ProcessRepository repository) throws IOException {
        this.repository = repository;
        this.serverSocket = new ServerSocket(0); // 0 = OS assigns a free port
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jlloc-socket-handler");
            t.setDaemon(true);
            return t;
        });
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void start() throws IOException {
        // Write the port to disk so the CLI can find it
        Files.createDirectories(PORT_FILE.getParent());
        Files.writeString(PORT_FILE, String.valueOf(getPort()));

        executor.submit(this::acceptLoop);
    }

    public void stop() throws IOException {
        serverSocket.close();
        Files.deleteIfExists(PORT_FILE);
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("[jlloc-socket] accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             ObjectInputStream in = new ObjectInputStream(client.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream())) {

            Command command = (Command) in.readObject();
            Response response;
            try {
                response = dispatch(command);
            } catch (Throwable t) {
                // A handler threw something unexpected (including an Error
                // subtype from a native call like oshi). Rather than let
                // the connection die silently, which the CLI can only
                // report as a confusing null-message failure, send back
                // a real ErrorResponse so the person sees what happened.
                response = new ErrorResponse("Internal error handling "
                        + command.getClass().getSimpleName() + ": " + t);
            }
            out.writeObject(response);
            out.flush();

        } catch (Exception e) {
            // Client disconnected or sent garbage, not worth logging
            // at error level, this is expected during CLI restarts
        }
    }

    private Response dispatch(Command command) {
        return switch (command) {
            case StatusCommand c  -> handleStatus();
            case ExplainCommand c -> handleExplain(c.service());
            case DumpCommand c    -> handleDump(c.service());
            case FixCommand c     -> handleFix(c.service(), c.targetHeapMb());
        };
    }

    private Response handleStatus() {
        List<ProcessSummary> summaries = repository.all().stream()
                .map(this::toSummary)
                .toList();

        long totalRam = 0;
        long availableRam = 0;
        try {
            var memory = new SystemInfo().getHardware().getMemory();
            totalRam = memory.getTotal();
            availableRam = memory.getAvailable();
        } catch (Throwable t) {
            // oshi can throw Error subtypes (UnsatisfiedLinkError, native
            // WMI failures) on Windows, not just Exception. Catching
            // Throwable here means a flaky RAM query degrades to "0 used /
            // 0 total" (CLI already handles that) instead of killing the
            // whole status response.
        }

        return new StatusResponse(summaries, totalRam, availableRam, "0.1.0");
    }

    private Response handleExplain(String service) {
        Optional<ProcessRepository.ProcessRecord> record = findByService(service);
        if (record.isEmpty()) {
            return new ErrorResponse("No process found matching: " + service
                    + ". Run 'jlloc status' to see monitored services.");
        }

        ProcessRepository.ProcessRecord r = record.get();
        DiagnosisResult d = r.diagnosis();
        MemorySignal signal = r.lastSignal();

        if (d == null || signal == null) {
            return new ErrorResponse("Still collecting data for: " + service
                    + ". Try again in a few seconds.");
        }

        ProcessSummary summary = toSummary(r);

        // Get the fully-formed, number-specific recommendation
        RecommendationEngine recEngine = new RecommendationEngine();
        RecommendationEngine.Recommendation rec = recEngine.recommend(d, signal, summary.appName());

        // Pre-formatted signal lines via DiagnosisFormatter, so the CLI
        // doesn't need to re-derive labels/thresholds itself
        DiagnosisFormatter formatter = new DiagnosisFormatter();
        DiagnosisFormatter.HeapStatsSnapshot heapSnapshot = r.heapStats() != null
                ? new DiagnosisFormatter.HeapStatsSnapshot(r.heapStats().usedBytes(), r.heapStats().maxBytes())
                : null;
        DiagnosisFormatter.ExplainBlock block = formatter.buildExplainBlock(d, signal, rec, heapSnapshot);

        List<String> signalLines = new java.util.ArrayList<>();
        addIfPresent(signalLines, block.heapLine());
        addIfPresent(signalLines, block.gcLine());
        addIfPresent(signalLines, block.allocLine());
        addIfPresent(signalLines, block.leakLine());
        addIfPresent(signalLines, block.slopeLine());
        addIfPresent(signalLines, block.metaspaceLine());
        addIfPresent(signalLines, block.directBufferLine());
        addIfPresent(signalLines, block.rssLine());
        addIfPresent(signalLines, block.swapLine());

        return new ExplainResponse(
                summary,

                signal.gcTimeRatio(),
                signal.postGcFloorSlopePerSecond(),
                signal.allocationRateBytesPerSecond(),

                signal.metaspaceUsedBytes(),
                signal.directBufferUsedBytes(),
                signal.codeCacheUsedBytes(),

                signal.rssBytes(),
                signal.swapInRateBytesPerSecond(),
                signal.swapOutRateBytesPerSecond(),
                signal.majorPageFaultsPerSecond(),

                signal.containerMemoryLimitBytes(),
                signal.containerMemoryPressure(),

                rec.id() != null ? rec.id().name() : null,
                rec.shortAdvice(),
                rec.fullAdvice(),
                rec.command(),

                signal.sampleCount(),
                6,   // MIN_SAMPLES, consider exposing this as a public constant
                // on DiagnosisEngine instead of duplicating "6" here

                signalLines
        );
    }

    private static void addIfPresent(List<String> lines, String line) {
        if (line != null) lines.add(line);
    }

    private Response handleDump(String service) {
        Optional<ProcessRepository.ProcessRecord> record = findByService(service);
        if (record.isEmpty()) {
            return new ErrorResponse("No process found matching: " + service);
        }

        long pid = record.get().pid();
        Path dumpDir = Path.of(System.getProperty("user.home"), ".jlloc", "dumps");

        try {
            Files.createDirectories(dumpDir);
            Path dumpFile = dumpDir.resolve(service + "-" + System.currentTimeMillis() + ".hprof");

            // Use jcmd to trigger the heap dump — ships with every JDK,
            // no extra tooling required
            Process proc = new ProcessBuilder(
                    "jcmd", String.valueOf(pid),
                    "GC.heap_dump", dumpFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            proc.waitFor();

            long size = Files.exists(dumpFile) ? Files.size(dumpFile) : 0;
            return new DumpResponse(service, dumpFile.toAbsolutePath().toString(), size);

        } catch (Exception e) {
            return new ErrorResponse("Heap dump failed for " + service + ": " + e.getMessage());
        }
    }

    private Response handleFix(String service, int targetHeapMb) {
        // CRaC integration comes in Phase 4 — placeholder for now
        // so the protocol is complete and the CLI can call this
        return new ErrorResponse(
                "jlloc fix is not yet implemented (Phase 4 — CRaC integration). "
                        + "Run 'jlloc dump " + service + "' to capture a heap dump now.");
    }

    /**
     * Resolves a service identifier, either a PID string or an app name
     * substring, to a ProcessRecord. Case-insensitive substring match
     * on app name so "auth" matches "AuthServiceApplication".
     */
    private Optional<ProcessRepository.ProcessRecord> findByService(String service) {
        // Try PID first
        try {
            long pid = Long.parseLong(service);
            return repository.get(pid);
        } catch (NumberFormatException ignored) {
        }

        // App name substring match, case-insensitive
        String lower = service.toLowerCase();
        return repository.all().stream()
                .filter(r -> r.classification() != null
                        && r.classification().appName().toLowerCase().contains(lower))
                .findFirst();
    }

    private ProcessSummary toSummary(ProcessRepository.ProcessRecord r) {
        String appName = r.classification() != null ? r.classification().appName() : "unknown";
        String category = r.classification() != null ? r.classification().category() : "unknown";
        int weight = r.classification() != null ? r.classification().priorityWeight() : 0;

        long used = r.heapStats() != null ? r.heapStats().usedBytes() : 0;
        long max = r.heapStats() != null ? r.heapStats().maxBytes() : 0;

        boolean collecting = r.diagnosis() == null
                || r.diagnosis().reason().startsWith("collecting");

        String severity = r.diagnosis() != null
                ? r.diagnosis().severity().name() : "NORMAL";
        String diagnosis = r.diagnosis() != null
                ? r.diagnosis().diagnosis().name() : "UNKNOWN";

        int leakSignal = 0, loadSignal = 0, gcSignal = 0;
        if (r.diagnosis() != null && r.diagnosis().signalStrengths() != null) {
            leakSignal = r.diagnosis().signalStrengths().leakSignal();
            loadSignal = r.diagnosis().signalStrengths().loadSignal();
            gcSignal   = r.diagnosis().signalStrengths().gcPressureSignal();
        }

        String reason = r.diagnosis() != null ? r.diagnosis().reason() : null;
        String recommendationId = r.diagnosis() != null ? r.diagnosis().recommendationId().shortDescription() : null;

        return new ProcessSummary(
                r.pid(), appName, category, weight,
                used, max,
                severity, diagnosis,
                leakSignal, loadSignal, gcSignal,
                reason, recommendationId,
                collecting
        );
    }
}