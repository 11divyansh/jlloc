package com.jlloc.cli;

import com.jlloc.common.protocol.Command;
import com.jlloc.common.protocol.ProcessSummary;
import com.jlloc.common.protocol.Response;
import com.jlloc.common.protocol.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI entry point. Sends commands to the daemon over the socket and
 * formats responses for the terminal.
 *
 * All formatting lives here, not in the daemon. The daemon returns
 * structured data. The CLI decides how to present it.
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

    // status

    private static int formatStatus(StatusResponse r) {
        int maxNameLen = r.processes().stream()
                .mapToInt(p -> p.appName().length())
                .max().orElse(20);
        maxNameLen = Math.max(maxNameLen, 20);

        System.out.println();
        System.out.printf("jlloc — %d JVMs monitored%n", r.processes().size());
        if (r.totalRamBytes() > 0) {
            System.out.printf("System RAM: %s used / %s total%n",
                    humanBytes(r.totalRamBytes() - r.availableRamBytes()),
                    humanBytes(r.totalRamBytes()));
        }
        System.out.println();

        String hdr = "  %-8s  %-" + maxNameLen + "s  %-8s  %-20s  %s%n";
        System.out.printf(hdr, "PID", "APP", "HEAP", "STATUS", "");
        System.out.printf(hdr,
                "--------", "-".repeat(maxNameLen),
                "--------", "--------------------", "");

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

    // explain

    private static int formatExplain(ExplainResponse r) {
        ProcessSummary p = r.process();
        System.out.println();
        System.out.printf("  %s  (PID %d)%n", p.appName(), p.pid());
        System.out.println("  " + "-".repeat(52));
        System.out.printf("  Severity:    %s%n", p.severity());
        System.out.printf("  Diagnosis:   %s%n", p.diagnosis());
        System.out.println();

        if (!r.hasSufficientData()) {
            System.out.printf("  Still collecting data — %d/%d samples minimum%n",
                    r.sampleCount(), r.minSamplesRequired());
            System.out.println();
            return 0;
        }

        // Signal lines use pre-formatted list from DiagnosisFormatter
        // if available, otherwise fall back to raw numbers
        List<String> lines = r.signalLines();
        if (lines != null && !lines.isEmpty()) {
            for (String line : lines) {
                System.out.println("  " + line);
            }
        } else {
            // Fallback rendering from raw signal fields
            System.out.printf("  Heap ........... %.1f%%  (%s / %s)%n",
                    p.heapUsedPercent(),
                    humanBytes(p.heapUsedBytes()),
                    humanBytes(p.heapMaxBytes()));

            if (r.gcTimeRatio() >= 0) {
                System.out.printf("  GC Pressure .... %.1f%% of CPU%n",
                        r.gcTimeRatio() * 100);
            }

            if (r.hasOffHeapSignals()) {
                System.out.println();
                if (r.metaspaceUsedBytes() >= 0) {
                    System.out.printf("  Metaspace ...... %s%n",
                            humanBytes(r.metaspaceUsedBytes()));
                }
                if (r.directBufferUsedBytes() >= 0) {
                    String note = r.directBufferUsedBytes() > 200_000_000
                            ? "  ↑ growing (Netty?)" : "";
                    System.out.printf("  Direct buffers . %s%s%n",
                            humanBytes(r.directBufferUsedBytes()), note);
                }
            }

            if (r.rssBytes() >= 0) {
                System.out.println();
                if (r.hasContainerSignal()) {
                    String pressureNote = r.containerPressure() > 0.85
                            ? "  ← this is why" : "";
                    System.out.printf("  RSS ............ %s / %s container limit%s%n",
                            humanBytes(r.rssBytes()),
                            humanBytes(r.containerLimitBytes()),
                            pressureNote);
                } else {
                    System.out.printf("  RSS ............ %s%n", humanBytes(r.rssBytes()));
                }
            }

            if (r.isThrashing()) {
                System.out.printf("  Swap ........... in=%.1f MB/s  out=%.1f MB/s  ← thrashing%n",
                        r.swapInRateBytesPerSec() / (1024.0 * 1024.0),
                        r.swapOutRateBytesPerSec() / (1024.0 * 1024.0));
            }

            if (r.floorSlopeBytesPerSec() != 0) {
                System.out.printf("  Floor slope .... %+.1f KB/s%n",
                        r.floorSlopeBytesPerSec() / 1024.0);
            }

            if (r.allocationRateBytesPerSec() >= 0) {
                System.out.printf("  Allocation ..... %.2f MB/s%n",
                        r.allocationRateBytesPerSec() / (1024.0 * 1024.0));
            }
        }

        // Recommendation block
        if (r.shortAdvice() != null) {
            System.out.println();
            System.out.println("  " + "-".repeat(52));
            System.out.println("  -> " + r.shortAdvice());

            if (r.fullAdvice() != null && !r.fullAdvice().equals(r.shortAdvice())) {
                System.out.println();
                for (String line : r.fullAdvice().split("\n")) {
                    System.out.println("    " + line);
                }
            }

            if (r.command() != null) {
                System.out.println();
                System.out.println("  Command:");
                System.out.println("    " + r.command());
            }
        }

        System.out.println();
        return switch (p.severity()) {
            case "CRITICAL" -> 2;
            case "WARNING"  -> 1;
            default         -> 0;
        };
    }

    // dump / fix

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
            case "CRITICAL" -> "!! act now";
            case "WARNING"  -> switch (p.diagnosis()) {
                case "LEAK"                -> "^  jlloc dump " + p.appName();
                case "LOAD"                -> "^  jlloc fix " + p.appName();
                case "HOST_MEMORY_PRESSURE"-> "^  jlloc explain " + p.appName();
                default                    -> "^  elevated";
            };
            default -> "";
        };
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0)                  return "?";
        if (bytes >= 1_073_741_824L) return String.format("%.1fGB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1fMB", bytes / 1_048_576.0);
        if (bytes >= 1_024L)         return String.format("%.1fKB", bytes / 1_024.0);
        return bytes + "B";
    }

    private static void printUsage() {
        System.out.println("Usage: jlloc <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  status                  Show all monitored JVMs");
        System.out.println("  explain <service>       Full diagnosis for one service");
        System.out.println("  dump <service>          Trigger a heap dump");
        System.out.println("  fix <service>           Resize heap (Phase 6 — CRaC)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  jlloc status");
        System.out.println("  jlloc explain auth-service");
        System.out.println("  jlloc explain 27768");
        System.out.println("  jlloc dump elasticsearch");
    }

    private static Command fail(String message) {
        System.err.println(message);
        return null;
    }
}