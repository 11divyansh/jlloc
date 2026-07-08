package com.jlloc.cli;

import com.jlloc.common.protocol.Command;
import com.jlloc.common.protocol.ProcessSummary;
import com.jlloc.common.protocol.Response;
import com.jlloc.common.protocol.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The CLI entry point. Reads a command from args, sends it to the
 * daemon over the socket, and formats the response for the terminal.
 *
 * Formatting lives here, not in the daemon. The daemon returns data.
 * The CLI decides how to present it. This separation means a future
 * web UI, VSCode extension, or REST API can reuse the same daemon
 * responses without any daemon changes.
 */
public class CliMain {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        Command command = parseCommand(args);
        if (command == null) {
            printUsage();
            System.exit(1);
        }

        Response response = sendToDaemon(command);
        if (response == null) {
            System.exit(1);
        }

        System.exit(formatResponse(response));
    }

    private static Command parseCommand(String[] args) {
        return switch (args[0].toLowerCase()) {
            case "status"  -> new StatusCommand();
            case "explain" -> args.length > 1
                    ? new ExplainCommand(args[1])
                    : fail("explain requires a service name: jlloc explain <service>");
            case "dump"    -> args.length > 1
                    ? new DumpCommand(args[1])
                    : fail("dump requires a service name: jlloc dump <service>");
            case "fix"     -> args.length > 1
                    ? new FixCommand(args[1], 0)
                    : fail("fix requires a service name: jlloc fix <service>");
            default -> {
                System.err.println("Unknown command: " + args[0]);
                yield null;
            }
        };
    }

    private static Response sendToDaemon(Command command) {
        Path portFile = Path.of(System.getProperty("user.home"), ".jlloc", "daemon.port");

        int port;
        try {
            port = Integer.parseInt(Files.readString(portFile).trim());
        } catch (Exception e) {
            System.err.println("jlloc daemon is not running.");
            System.err.println("Start it with: ./gradlew :jlloc-daemon:run");
            return null;
        }

        try (Socket socket = new Socket("127.0.0.1", port);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject(command);
            out.flush();
            return (Response) in.readObject();

        } catch (Exception e) {
            System.err.println("Failed to connect to daemon: " + e.getMessage());
            return null;
        }
    }

    private static int formatResponse(Response response) {
        return switch (response) {
            case StatusResponse r  -> formatStatus(r);
            case ExplainResponse r -> formatExplain(r);
            case DumpResponse r    -> formatDump(r);
            case FixResponse r     -> formatFix(r);
            case ErrorResponse r   -> {
                System.err.println("Error: " + r.message());
                yield 1;
            }
        };
    }

    private static int formatStatus(StatusResponse r) {
        int maxNameLen = r.processes().stream()
                .mapToInt(p -> p.appName().length())
                .max().orElse(20);
        maxNameLen = Math.max(maxNameLen, 20);

        System.out.println();
        System.out.printf("jlloc - %d JVMs monitored%n", r.processes().size());
        if (r.totalRamBytes() > 0) {
            System.out.printf("System RAM: %s used / %s total%n",
                    humanBytes(r.totalRamBytes() - r.availableRamBytes()),
                    humanBytes(r.totalRamBytes()));
        }
        System.out.println();

        String hdr = "  %-8s  %-" + maxNameLen + "s  %-8s  %-18s  %s%n";
        System.out.printf(hdr, "PID", "APP", "HEAP", "STATUS", "");
        System.out.printf(hdr,
                "--------", "-".repeat(maxNameLen),
                "--------", "------------------", "");

        for (ProcessSummary p : r.processes()) {
            String heapStr = p.stillCollecting() ? "?"
                    : String.format("%.1f%%", p.heapUsedPercent());
            String status = p.stillCollecting() ? "collecting..."
                    : p.severity() + " / " + p.diagnosis();
            String flag = p.stillCollecting() ? "" : alertFlag(p);

            System.out.printf(hdr,
                    p.pid(), p.appName(), heapStr, status, flag);
        }
        System.out.println();
        return 0;
    }

    private static int formatExplain(ExplainResponse r) {
        ProcessSummary p = r.process();
        System.out.println();
        System.out.printf("  %s  (PID %d)%n", p.appName(), p.pid());
        System.out.println("  " + "─".repeat(50));
        System.out.printf("  Severity:    %s%n", p.severity());
        System.out.printf("  Diagnosis:   %s%n", p.diagnosis());
        System.out.println();

        if (p.reason() != null) {
            for (String line : p.reason().split("\n")) {
                System.out.println("  " + line);
            }
        }

        if (p.recommendation() != null) {
            System.out.println();
            System.out.println("  Recommendation:");
            for (String line : p.recommendation().split("\n")) {
                System.out.println("    " + line);
            }
        }
        System.out.println();

        return switch (p.severity()) {
            case "CRITICAL" -> 2;
            case "WARNING"  -> 1;
            default         -> 0;
        };
    }

    private static int formatDump(DumpResponse r) {
        System.out.printf("Heap dump written: %s (%s)%n",
                r.hprofPath(), humanBytes(r.dumpSizeBytes()));
        System.out.println("Open with: Eclipse MAT, VisualVM, or https://heaphero.io");
        return 0;
    }

    private static int formatFix(FixResponse r) {
        if (r.success()) {
            System.out.printf("Heap resized for %s: %s → %s (pause: %dms)%n",
                    r.service(),
                    humanBytes(r.previousHeapMaxBytes()),
                    humanBytes(r.newHeapMaxBytes()),
                    r.pauseMs());
            return 0;
        }
        System.err.println("Fix failed for " + r.service());
        return 1;
    }

    private static String alertFlag(ProcessSummary p) {
        return switch (p.severity()) {
            case "CRITICAL" -> "⚠  act now";
            case "WARNING"  -> switch (p.diagnosis()) {
                case "LEAK" -> "↑  jlloc dump " + p.appName();
                case "LOAD" -> "↑  jlloc fix " + p.appName();
                default     -> "↑  elevated";
            };
            default -> "";
        };
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024L)         return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }

    private static void printUsage() {
        System.out.println("Usage: jlloc <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  status                  Show all monitored JVMs");
        System.out.println("  explain <service>       Full diagnosis for one service");
        System.out.println("  dump <service>          Trigger a heap dump");
        System.out.println("  fix <service>           Resize heap (Phase 4 — not yet)");
    }

    private static Command fail(String message) {
        System.err.println(message);
        return null;
    }
}