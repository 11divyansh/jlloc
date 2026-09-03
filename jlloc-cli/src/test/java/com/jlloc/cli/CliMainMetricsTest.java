package com.jlloc.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliMainMetricsTest {

    @Test
    void runMetricsPrintsEndpointAndHealthyStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = (
                    "# HELP jlloc_heap_used_ratio Ratio of heap memory used to maximum heap\n"
                            + "# TYPE jlloc_heap_used_ratio gauge\n"
                            + "jlloc_heap_used_ratio{app=\"demo\",pid=\"123\"} 0.5\n"
                            + "# HELP jlloc_diagnosis Current diagnosis category, value always 1 (info-style metric)\n"
                            + "# TYPE jlloc_diagnosis gauge\n"
                            + "jlloc_diagnosis{app=\"demo\",pid=\"123\",diagnosis=\"HEALTHY\"} 1\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        Path tempHome = Files.createTempDirectory("jlloc-cli-metrics-test");
        Path metricsInfo = tempHome.resolve("metrics.info");
        Files.writeString(metricsInfo, "bind=127.0.0.1\nport=" + server.getAddress().getPort() + "\n");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));

            int exitCode = CliMain.runMetrics(metricsInfo, HttpClient.newHttpClient());

            assertEquals(0, exitCode);
            String output = stdout.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("jlloc metrics"));
            assertTrue(output.contains("endpoint: http://127.0.0.1:" + server.getAddress().getPort() + "/metrics"));
            assertTrue(output.contains("status:   UP"));
            assertTrue(output.contains("families: 2"));
            assertTrue(output.contains("series:   2"));
        } finally {
            System.setOut(originalOut);
            server.stop(0);
        }
    }
}
