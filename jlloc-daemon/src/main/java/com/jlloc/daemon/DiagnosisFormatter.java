package com.jlloc.daemon;

/**
 * Generates plain-language reason text from a DiagnosisResult and
 * the MemorySignal that produced it.
 */
public class DiagnosisFormatter {

    /**
     * Builds the reason block shown by `jlloc explain`.
     * Returns structured lines the CLI formats into its layout.
     */
    public ExplainBlock buildExplainBlock(DiagnosisResult result,
                                          MemorySignal signal,
                                          RecommendationEngine.Recommendation recommendation,
                                          HeapStatsSnapshot heapStats) {
        String heapLine = heapStats != null
                ? String.format("Heap ........... %.1f%%  (%s / %s)",
                heapStats.usedPercent(),
                humanBytes(heapStats.usedBytes()),
                humanBytes(heapStats.maxBytes()))
                : String.format("Heap ........... %.1f%%", signal.heapUsedRatio() * 100);

        String gcLine = signal.isGcRatioAvailable()
                ? String.format("GC Pressure .... %s (%.1f%% of CPU)",
                result.signalStrengths() != null
                        ? result.signalStrengths().gcLabel() : "?",
                signal.gcTimeRatio() * 100)
                : "GC Pressure .... unavailable";

        String allocLine = signal.isAllocationRateAvailable()
                ? String.format("Allocation ..... %s (%.1f MB/s)",
                allocationLabel(signal.allocationRateBytesPerSecond()),
                signal.allocationRateBytesPerSecond() / (1024.0 * 1024.0))
                : "Allocation ..... unavailable";

        String leakLine = result.signalStrengths() != null
                ? String.format("Leak signal .... %s (%d/100)",
                result.signalStrengths().leakLabel(),
                result.signalStrengths().leakSignal())
                : null;

        String slopeLine = signal.isFloorSlopeAvailable()
                ? String.format("Floor slope .... %+.1f KB/s",
                signal.postGcFloorSlopePerSecond() / 1024.0)
                : null;

        // Off-heap lines only shown when data is available
        // These are the lines that explain HOST_MEMORY_PRESSURE
        String metaspaceLine = signal.isMetaspaceAvailable()
                ? String.format("Metaspace ...... %s",
                humanBytes(signal.metaspaceUsedBytes()))
                : null;

        String directLine = signal.isDirectBufferAvailable()
                ? String.format("Direct buffers . %s%s",
                humanBytes(signal.directBufferUsedBytes()),
                signal.directBufferUsedBytes() > 200_000_000 ? "  ↑ (Netty?)" : "")
                : null;

        String rssLine = null;
        if (signal.isRssAvailable() && signal.isContainerSignalAvailable()) {
            rssLine = String.format("RSS ............ %s / %s container limit  %s",
                    humanBytes(signal.rssBytes()),
                    humanBytes(signal.containerMemoryLimitBytes()),
                    signal.containerMemoryPressure() > 0.85 ? "<-pressure here" : "");
        } else if (signal.isRssAvailable()) {
            rssLine = String.format("RSS ............ %s", humanBytes(signal.rssBytes()));
        }

        String swapLine = null;
        if (signal.isSwapRateAvailable() && signal.isThrashingIndicated()) {
            swapLine = String.format("Swap rate ...... in=%.1f MB/s  out=%.1f MB/s <-thrashing",
                    signal.swapInRateBytesPerSecond() / (1024.0 * 1024.0),
                    signal.swapOutRateBytesPerSecond() / (1024.0 * 1024.0));
        }

        return new ExplainBlock(
                heapLine, gcLine, allocLine, leakLine, slopeLine,
                metaspaceLine, directLine, rssLine, swapLine,
                recommendation.shortAdvice(),
                recommendation.fullAdvice(),
                recommendation.command()
        );
    }

    /**
     * Builds the compact one-line reason for `jlloc status` normal output.
     */
    public String buildStatusLine(DiagnosisResult result, MemorySignal signal) {
        return switch (result.diagnosis()) {
            case HEALTHY -> String.format("Heap: %.1f%%  GC: %.1f%%",
                    signal.heapUsedRatio() * 100,
                    signal.isGcRatioAvailable() ? signal.gcTimeRatio() * 100 : 0.0);
            case LOAD -> String.format("High load — heap at %.1f%%",
                    signal.heapUsedRatio() * 100);
            case LEAK -> String.format("Possible leak — floor rising %+.1f KB/s",
                    signal.isFloorSlopeAvailable()
                            ? signal.postGcFloorSlopePerSecond() / 1024.0 : 0.0);
            case HOST_MEMORY_PRESSURE -> signal.isContainerSignalAvailable()
                    ? String.format("RSS at %.0f%% of container limit",
                    signal.containerMemoryPressure() * 100)
                    : "Host memory pressure";
            case UNKNOWN -> "Insufficient data — collecting signals";
        };
    }

    private static String allocationLabel(double bytesPerSecond) {
        double mb = bytesPerSecond / (1024.0 * 1024.0);
        if (mb > 50) return "Very High";
        if (mb > 10) return "High";
        if (mb > 1)  return "Moderate";
        if (mb > 0.1) return "Low";
        return "Minimal";
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format("%.1fGB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1fMB", bytes / 1_048_576.0);
        if (bytes >= 1_024L)         return String.format("%.1fKB", bytes / 1_024.0);
        return bytes + "B";
    }

    /**
     * Snapshot of heap stats for formatting purposes.
     * Passed in from the ProcessRepository so formatter doesn't
     * need to reach into any other component.
     */
    public record HeapStatsSnapshot(long usedBytes, long maxBytes) {
        public double usedPercent() {
            return maxBytes > 0 ? usedBytes * 100.0 / maxBytes : 0.0;
        }
    }

    /**
     * All the lines needed to render a full `jlloc explain` block.
     * Null fields are omitted by the CLI formatter.
     */
    public record ExplainBlock(
            String heapLine,
            String gcLine,
            String allocLine,
            String leakLine,          // null if not relevant
            String slopeLine,         // null if unavailable
            String metaspaceLine,     // null if unavailable
            String directBufferLine,  // null if unavailable
            String rssLine,           // null if unavailable
            String swapLine,          // null if no thrashing
            String shortAdvice,
            String fullAdvice,
            String command            // null if no CLI command
    ) {}
}