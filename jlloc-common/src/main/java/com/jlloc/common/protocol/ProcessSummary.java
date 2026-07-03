package com.jlloc.common.protocol;

import java.io.Serializable;

/**
 * Everything the CLI needs to display one JVM process in a status
 * view. Pure data, no formatting, no terminal codes, no opinion
 * about how wide columns should be. The CLI formatter decides all
 * of that.
 */
public record ProcessSummary(
        long pid,
        String appName,
        String category,
        int priorityWeight,

        // heap
        long heapUsedBytes,
        long heapMaxBytes,

        // diagnosis - two-axis model
        String severity,    // NORMAL / WARNING / CRITICAL
        String diagnosis,   // HEALTHY / LOAD / LEAK / UNKNOWN

        // signal strengths 0-100, not percentages
        int leakSignal,
        int loadSignal,
        int gcPressureSignal,

        // plain-language reason and recommendation
        // null if not yet available or not applicable
        String reason,
        String recommendation,

        boolean stillCollecting
) implements Serializable {

    public double heapUsedPercent() {
        if (heapMaxBytes <= 0) return 0.0;
        return heapUsedBytes * 100.0 / heapMaxBytes;
    }
}