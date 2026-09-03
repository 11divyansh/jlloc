package com.jlloc.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves jlloc's current diagnosis state as Prometheus-format metrics,
 * so KEDA/HPA/VPA (or a human, or a script) can act on it directly —
 * jlloc does not build a scaler itself, it just exposes the verdict.
 *
 * IMPORTANT: Prometheus's text exposition format requires every line
 * for a given metric name to be grouped together — HELP, then TYPE,
 * then every series for that metric — before moving to the next
 * metric name. Interleaving (emitting metric A for every process,
 * then metric B for every process, mixed together) is NOT just
 * untidy — strict parsers (including Prometheus's own scraper and
 * KEDA's Prometheus-based scaler) reject the whole scrape as invalid
 * if metric families aren't grouped. This class builds each metric
 * family fully before writing it out, specifically to avoid that.
 */
public class MetricsServer {

    private static final int DEFAULT_PORT = 8001;
    static final Path INFO_FILE = Path.of(System.getProperty("user.home"), ".jlloc", "metrics.info");

    private final HttpServer server;
    private final ProcessRepository repository;
    private final String bindAddress;

    public MetricsServer(ProcessRepository repository) throws IOException {
        this(repository, resolvePort(), resolveBindAddress());
    }

    public MetricsServer(ProcessRepository repository, int port, String bindAddress) throws IOException {
        this.repository = repository;
        this.bindAddress = bindAddress;
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.createContext("/metrics", new MetricsHandler());
        this.server.setExecutor(null);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public String getBindAddress() {
        return server.getAddress().getHostString();
    }

    public void start() {
        try {
            Files.createDirectories(INFO_FILE.getParent());
            Files.writeString(INFO_FILE, "bind=" + bindAddress + System.lineSeparator()
                    + "port=" + getPort() + System.lineSeparator());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write metrics info file", e);
        }
        server.start();
    }

    public void stop() {
        server.stop(0);
        try {
            Files.deleteIfExists(INFO_FILE);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private static int resolvePort() {
        String env = System.getenv("JLLOC_METRICS_PORT");
        if (env != null) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * Defaults to loopback-only, matching DaemonSocketServer's own
     * design (TCP localhost). Binding to 0.0.0.0 exposes every
     * monitored JVM's memory diagnostics to anyone on the network —
     * that should be an explicit choice (e.g. a K8s sidecar scraped
     * by an in-cluster Prometheus), never a silent default.
     */
    private static String resolveBindAddress() {
        String env = System.getenv("JLLOC_METRICS_BIND");
        return env != null ? env.trim() : "127.0.0.1";
    }

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String body = renderMetrics();
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    /**
     * Builds the full /metrics body, one metric family at a time so
     * HELP/TYPE/series stay grouped per the exposition format spec.
     */
    private String renderMetrics() {
        List<ProcessRepository.ProcessRecord> records = List.copyOf(repository.all());

        StringBuilder sb = new StringBuilder();

        appendFamily(sb, "jlloc_heap_used_ratio", "gauge",
                "Ratio of heap memory used to maximum heap", records, r -> {
                    if (r.heapStats() == null) return null;
                    double ratio = (double) r.heapStats().usedBytes()
                            / Math.max(1, r.heapStats().maxBytes());
                    return ratio;
                });

        appendFamily(sb, "jlloc_leak_signal", "gauge",
                "Leak signal strength, 0-100 (not a percentage or probability)", records, r -> {
                    if (r.diagnosis() == null || r.diagnosis().signalStrengths() == null) return null;
                    return (double) r.diagnosis().signalStrengths().leakSignal();
                });

        appendFamily(sb, "jlloc_load_signal", "gauge",
                "Load signal strength, 0-100 (not a percentage or probability)", records, r -> {
                    if (r.diagnosis() == null || r.diagnosis().signalStrengths() == null) return null;
                    return (double) r.diagnosis().signalStrengths().loadSignal();
                });

        appendFamily(sb, "jlloc_container_pressure", "gauge",
                "Ratio of RSS to container memory limit, when running in a container", records, r -> {
                    if (r.lastSignal() == null || !r.lastSignal().isContainerSignalAvailable()) return null;
                    return r.lastSignal().containerMemoryPressure();
                });

        appendFamily(sb, "jlloc_severity", "gauge",
                "Diagnosis urgency: 0=NORMAL, 1=WARNING, 2=CRITICAL", records, r -> {
                    if (r.diagnosis() == null) return null;
                    return (double) severityLevel(r.diagnosis().severity());
                });

        // jlloc_diagnosis carries the WHY, not just the urgency — this is
        // the metric that lets a consumer distinguish "scale horizontally"
        // (LOAD) from "restart won't help, this is a leak" (LEAK) from
        // "don't touch heap, the host/container is the problem"
        // (HOST_MEMORY_PRESSURE). Severity alone can't express that.
        appendDiagnosisFamily(sb, records);

        return sb.toString();
    }

    private interface MetricValueFn {
        Double valueFor(ProcessRepository.ProcessRecord record);
    }

    private void appendFamily(StringBuilder sb, String name, String type, String help,
                              List<ProcessRepository.ProcessRecord> records, MetricValueFn fn) {
        List<String> lines = new ArrayList<>();
        for (ProcessRepository.ProcessRecord r : records) {
            Double value = fn.valueFor(r);
            if (value == null) continue; // skip processes where this metric doesn't apply
            lines.add(formatSeries(name, r, value));
        }
        if (lines.isEmpty()) return; // don't emit HELP/TYPE for a family with zero series

        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        for (String line : lines) {
            sb.append(line).append('\n');
        }
    }

    private void appendDiagnosisFamily(StringBuilder sb, List<ProcessRepository.ProcessRecord> records) {
        List<String> lines = new ArrayList<>();
        for (ProcessRepository.ProcessRecord r : records) {
            if (r.diagnosis() == null) continue;
            String app = appNameOf(r);
            String diagnosis = r.diagnosis().diagnosis().name();
            lines.add(String.format(
                    "jlloc_diagnosis{app=\"%s\",pid=\"%d\",diagnosis=\"%s\"} 1",
                    escapeLabelValue(app), r.pid(), escapeLabelValue(diagnosis)));
        }
        if (lines.isEmpty()) return;

        sb.append("# HELP jlloc_diagnosis Current diagnosis category, value always 1 (info-style metric)\n");
        sb.append("# TYPE jlloc_diagnosis gauge\n");
        for (String line : lines) sb.append(line).append('\n');
    }

    private String formatSeries(String name, ProcessRepository.ProcessRecord r, double value) {
        String app = appNameOf(r);
        return String.format("%s{app=\"%s\",pid=\"%d\"} %s",
                name, escapeLabelValue(app), r.pid(), formatValue(value));
    }

    private static String appNameOf(ProcessRepository.ProcessRecord r) {
        return r.classification() != null ? r.classification().appName() : "unknown";
    }

    /**
     * Prometheus label values must escape backslash, double-quote, and
     * newline. jlloc's own history has produced appNames containing raw
     * Windows paths (backslashes) — this isn't a theoretical edge case
     * for this project specifically.
     */
    private static String escapeLabelValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private static String formatValue(double value) {
        // Avoid scientific notation / trailing garbage from raw Double.toString
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * Same NORMAL/WARNING/CRITICAL -> 0/1/2 mapping HeapMonitor already
     * uses internally for its own severity-worsening check. Duplicated
     * here rather than shared because HeapMonitor's version is
     * package-private; worth promoting to a shared utility if this
     * mapping needs to change in more than one place later.
     */
    private static int severityLevel(DiagnosisResult.Severity s) {
        return switch (s) {
            case NORMAL   -> 0;
            case WARNING  -> 1;
            case CRITICAL -> 2;
        };
    }
}
