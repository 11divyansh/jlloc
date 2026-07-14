package com.jlloc.common.protocol;

import java.util.List;

/**
 * Response to ExplainCommand. Contains everything the CLI needs to
 * render a full `jlloc explain <service>` block.
 */
public record ExplainResponse(
        ProcessSummary process,

        // JVM signals
        double gcTimeRatio,
        double floorSlopeBytesPerSec,
        double allocationRateBytesPerSec,

        // Off-heap signals (Layer 1 JMX)
        long metaspaceUsedBytes,
        long directBufferUsedBytes,
        long codeCacheUsedBytes,

        // OS signals (Layer 2 /proc)
        long rssBytes,
        double swapInRateBytesPerSec,
        double swapOutRateBytesPerSec,
        double majorFaultsPerSec,

        // Container signals (Layer 3 cgroup)
        long containerLimitBytes,
        double containerPressure,

        // Recommendation
        String recommendationId,
        String shortAdvice,
        String fullAdvice,
        String command,

        // Data quality
        int sampleCount,
        int minSamplesRequired,

        // Signal lines pre-formatted by DiagnosisFormatter
        // (list of non-null lines to display in order)
        List<String> signalLines

) implements Response {

    public boolean hasSufficientData() {
        return sampleCount >= minSamplesRequired;
    }

    public boolean hasContainerSignal() {
        return containerLimitBytes > 0;
    }

    public boolean hasOffHeapSignals() {
        return metaspaceUsedBytes >= 0 || directBufferUsedBytes >= 0;
    }

    public boolean isThrashing() {
        return swapInRateBytesPerSec > 1_000_000 || swapOutRateBytesPerSec > 1_000_000;
    }
}