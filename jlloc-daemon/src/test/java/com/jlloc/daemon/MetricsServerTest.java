package com.jlloc.daemon;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsServerTest {

    @Test
    void scrapeReturnsValidPrometheusText() throws Exception {
        ProcessRepository repository = new ProcessRepository();
        long pid = 4242L;

        repository.register(new JvmProcessWatcher.DetectedJvm(pid, "demo-service", Instant.EPOCH));
        repository.updateClassification(pid, new ProcessFingerprinter.Classification(pid, "application", "demo-service", 20));
        repository.updateCapabilities(pid, JvmCapabilities.full());
        repository.updateHeapStats(pid, new JmxConnector.HeapStats(
                pid,
                512L * 1024 * 1024,
                768L * 1024 * 1024,
                1024L * 1024 * 1024,
                new JmxConnector.GcStats(12L, 3456L)
        ));
        repository.updateLastSignal(pid, new MemorySignal(
                0.50,
                0.12,
                0.40,
                6_000,
                2_000_000,
                20
        ));
        repository.updateDiagnosis(pid, new DiagnosisResult(
                DiagnosisResult.Severity.NORMAL,
                DiagnosisResult.Diagnosis.HEALTHY,
                "all good",
                RecommendationId.NOTHING_REQUIRED,
                new DiagnosisResult.SignalStrengths(8, 12, 14, 50),
                Instant.now()
        ));

        MetricsServer server = new MetricsServer(repository, 0, "127.0.0.1");
        try {
            server.start();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.getPort() + "/metrics"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertNotNull(response.body());
            assertTrue(response.headers().firstValue("content-type")
                    .orElse("")
                    .contains("text/plain"));

            String body = response.body();
            assertTrue(body.contains("# HELP jlloc_heap_used_ratio"));
            assertTrue(body.contains("# TYPE jlloc_heap_used_ratio gauge"));
            assertTrue(body.contains("jlloc_heap_used_ratio{app=\"demo-service\",pid=\"4242\"} 0.5"));
            assertTrue(body.contains("jlloc_diagnosis{app=\"demo-service\",pid=\"4242\",diagnosis=\"HEALTHY\"} 1"));

            assertPrometheusFamiliesAreGrouped(body);
            assertPrometheusSeriesAreSyntacticallyValid(body);
        } finally {
            server.stop();
        }
    }

    private static void assertPrometheusFamiliesAreGrouped(String body) {
        List<String> lines = body.lines().filter(line -> !line.isBlank()).toList();
        String currentFamily = null;
        Set<String> closedFamilies = new HashSet<>();

        for (String line : lines) {
            String family = familyOf(line);
            if (family == null) {
                continue;
            }

            if (currentFamily == null) {
                currentFamily = family;
                continue;
            }

            if (!currentFamily.equals(family)) {
                closedFamilies.add(currentFamily);
                assertFalse(closedFamilies.contains(family),
                        "Metric family " + family + " reappeared after another family started: " + line);
                currentFamily = family;
            }
        }
    }

    private static void assertPrometheusSeriesAreSyntacticallyValid(String body) {
        for (String line : body.lines().filter(line -> !line.isBlank()).toList()) {
            if (line.startsWith("#")) {
                continue;
            }
            assertTrue(line.matches("^[a-zA-Z_:][a-zA-Z0-9_:]*(\\{.*\\})? [-+]?\\d+(?:\\.\\d+)?$"),
                    "Invalid Prometheus series line: " + line);
        }
    }

    private static String familyOf(String line) {
        if (line.startsWith("# HELP ") || line.startsWith("# TYPE ")) {
            String[] parts = line.split("\\s+", 4);
            return parts.length >= 3 ? parts[2] : null;
        }
        if (line.startsWith("#")) {
            return null;
        }

        int brace = line.indexOf('{');
        int space = line.indexOf(' ');
        if (space < 0) {
            return line;
        }
        if (brace >= 0 && brace < space) {
            return line.substring(0, brace);
        }
        return line.substring(0, space);
    }
}
