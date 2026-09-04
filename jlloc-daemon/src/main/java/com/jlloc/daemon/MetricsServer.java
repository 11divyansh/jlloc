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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        appendServiceRollupFamilies(sb, records);

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

    private void appendServiceRollupFamilies(StringBuilder sb, List<ProcessRepository.ProcessRecord> records) {
        List<ServiceRollup> services = rollupServices(records);

        appendServiceFamily(sb, "jlloc_service_process_count", "gauge",
                "Number of JVMs contributing to the service rollup", services,
                rollup -> (double) rollup.processCount);

        appendServiceFamily(sb, "jlloc_service_heap_used_bytes", "gauge",
                "Total heap used bytes across the service rollup", services,
                rollup -> (double) rollup.totalHeapUsedBytes);

        appendServiceFamily(sb, "jlloc_service_heap_max_bytes", "gauge",
                "Total heap max bytes across the service rollup", services,
                rollup -> (double) rollup.totalHeapMaxBytes);

        appendServiceFamily(sb, "jlloc_service_heap_used_ratio", "gauge",
                "Total heap used bytes divided by total heap max bytes for the service rollup",
                services, rollup -> {
                    if (rollup.totalHeapMaxBytes <= 0) return null;
                    return rollup.totalHeapUsedBytes * 1.0 / rollup.totalHeapMaxBytes;
                });

        appendServiceFamily(sb, "jlloc_service_rss_bytes", "gauge",
                "Total RSS bytes across the service rollup", services,
                rollup -> (double) rollup.totalRssBytes);

        appendServiceFamily(sb, "jlloc_service_container_limit_bytes", "gauge",
                "Total container memory limit bytes across the service rollup", services,
                rollup -> rollup.totalContainerLimitBytes > 0 ? (double) rollup.totalContainerLimitBytes : null);

        appendServiceFamily(sb, "jlloc_service_container_pressure", "gauge",
                "Total RSS divided by total container limit for the service rollup", services,
                rollup -> {
                    if (rollup.totalContainerLimitBytes <= 0) return null;
                    return rollup.totalRssBytes * 1.0 / rollup.totalContainerLimitBytes;
                });

        appendServiceDiagnosisFamily(sb, services);
        appendServiceScalingFamily(sb, services);
    }

    private String formatSeries(String name, ProcessRepository.ProcessRecord r, double value) {
        String app = appNameOf(r);
        return String.format("%s{app=\"%s\",pid=\"%d\"} %s",
                name, escapeLabelValue(app), r.pid(), formatValue(value));
    }

    private interface ServiceMetricValueFn {
        Double valueFor(ServiceRollup rollup);
    }

    private void appendServiceFamily(StringBuilder sb, String name, String type, String help,
                                     List<ServiceRollup> services, ServiceMetricValueFn fn) {
        List<String> lines = new ArrayList<>();
        for (ServiceRollup rollup : services) {
            Double value = fn.valueFor(rollup);
            if (value == null) continue;
            lines.add(String.format("%s{service=\"%s\"} %s",
                    name, escapeLabelValue(rollup.serviceName), formatValue(value)));
        }
        if (lines.isEmpty()) return;

        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendServiceDiagnosisFamily(StringBuilder sb, List<ServiceRollup> services) {
        List<String> lines = new ArrayList<>();
        for (ServiceRollup rollup : services) {
            if (rollup.worstDiagnosis == null) continue;
            lines.add(String.format(
                    "jlloc_service_diagnosis{service=\"%s\",severity=\"%s\",diagnosis=\"%s\"} 1",
                    escapeLabelValue(rollup.serviceName),
                    escapeLabelValue(rollup.worstDiagnosis.severity().name()),
                    escapeLabelValue(rollup.worstDiagnosis.diagnosis().name())));
        }
        if (lines.isEmpty()) return;

        sb.append("# HELP jlloc_service_diagnosis Current service-level diagnosis category, value always 1 (info-style metric)\n");
        sb.append("# TYPE jlloc_service_diagnosis gauge\n");
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendServiceScalingFamily(StringBuilder sb, List<ServiceRollup> services) {
        List<String> lines = new ArrayList<>();
        ScalingPolicyEngine policyEngine = new ScalingPolicyEngine();

        for (ServiceRollup rollup : services) {
            ScalingPolicyEngine.ScalingDecision decision = policyEngine.decide(
                    rollup.worstDiagnosis,
                    rollup.worstSignal,
                    rollup.serviceName);
            lines.add(String.format(
                    "jlloc_service_scaling_decision{service=\"%s\",axis=\"%s\",direction=\"%s\",recommendation=\"%s\"} 1",
                    escapeLabelValue(rollup.serviceName),
                    escapeLabelValue(decision.axis().name()),
                    escapeLabelValue(decision.direction().name()),
                    escapeLabelValue(decision.recommendationId().name())));
        }

        if (lines.isEmpty()) return;

        sb.append("# HELP jlloc_service_scaling_decision Service-level scaling contract: axis, direction, and recommendation ID\n");
        sb.append("# TYPE jlloc_service_scaling_decision gauge\n");
        for (String line : lines) sb.append(line).append('\n');
    }

    private List<ServiceRollup> rollupServices(List<ProcessRepository.ProcessRecord> records) {
        Map<String, List<ProcessRepository.ProcessRecord>> grouped = new LinkedHashMap<>();
        for (ProcessRepository.ProcessRecord record : records) {
            grouped.computeIfAbsent(appNameOf(record), key -> new ArrayList<>()).add(record);
        }

        List<ServiceRollup> services = new ArrayList<>();
        for (Map.Entry<String, List<ProcessRepository.ProcessRecord>> entry : grouped.entrySet()) {
            services.add(ServiceRollup.from(entry.getKey(), entry.getValue()));
        }
        return services;
    }

    private record ServiceRollup(
            String serviceName,
            int processCount,
            long totalHeapUsedBytes,
            long totalHeapMaxBytes,
            long totalRssBytes,
            long totalContainerLimitBytes,
            DiagnosisResult worstDiagnosis,
            MemorySignal worstSignal
    ) {
        private static ServiceRollup from(String serviceName, List<ProcessRepository.ProcessRecord> records) {
            int processCount = records.size();
            long totalHeapUsedBytes = 0;
            long totalHeapMaxBytes = 0;
            long totalRssBytes = 0;
            long totalContainerLimitBytes = 0;
            ProcessRepository.ProcessRecord worstRecord = null;

            for (ProcessRepository.ProcessRecord record : records) {
                if (record.heapStats() != null) {
                    totalHeapUsedBytes += record.heapStats().usedBytes();
                    totalHeapMaxBytes += record.heapStats().maxBytes();
                }
                if (record.lastSignal() != null) {
                    if (record.lastSignal().isRssAvailable()) {
                        totalRssBytes += Math.max(0, record.lastSignal().rssBytes());
                    }
                    if (record.lastSignal().isContainerSignalAvailable()) {
                        totalContainerLimitBytes += Math.max(0, record.lastSignal().containerMemoryLimitBytes());
                    }
                }
                worstRecord = worstOf(worstRecord, record);
            }

            DiagnosisResult worstDiagnosis = worstRecord != null ? worstRecord.diagnosis() : null;
            MemorySignal worstSignal = worstRecord != null ? worstRecord.lastSignal() : null;

            return new ServiceRollup(
                    serviceName,
                    processCount,
                    totalHeapUsedBytes,
                    totalHeapMaxBytes,
                    totalRssBytes,
                    totalContainerLimitBytes,
                    worstDiagnosis,
                    worstSignal
            );
        }

        private static ProcessRepository.ProcessRecord worstOf(
                ProcessRepository.ProcessRecord left,
                ProcessRepository.ProcessRecord right) {
            if (left == null) return right;
            if (right == null) return left;

            int leftSeverity = left.diagnosis() != null ? severityLevel(left.diagnosis().severity()) : -1;
            int rightSeverity = right.diagnosis() != null ? severityLevel(right.diagnosis().severity()) : -1;
            if (leftSeverity != rightSeverity) {
                return leftSeverity > rightSeverity ? left : right;
            }

            double leftHeap = left.heapStats() != null ? left.heapStats().usedPercentOfMax() : -1;
            double rightHeap = right.heapStats() != null ? right.heapStats().usedPercentOfMax() : -1;
            if (leftHeap != rightHeap) {
                return leftHeap > rightHeap ? left : right;
            }

            return left.pid() <= right.pid() ? left : right;
        }
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
