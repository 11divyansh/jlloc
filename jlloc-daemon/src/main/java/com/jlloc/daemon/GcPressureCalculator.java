package com.jlloc.daemon;

import java.time.Duration;
import java.util.List;

/**
 * Calculates GC pressure as "percentage of wall-clock time spent in
 * GC" over a sliding window of samples. This is your strongest early
 * warning signal for an approaching OOM.
 *
 * The numbers used here (gcTimeMs) are cumulative from JVM startup.
 * We delta between the oldest and newest sample in the window to get
 * "how much GC time accumulated in this window", then divide by the
 * wall-clock duration of the same window.
 */
public class GcPressureCalculator {

    // Standard JVM threshold - above 98% GC time with <2% heap
    // freed triggers OutOfMemoryError: GC overhead limit exceeded
    public static final double CRITICAL_THRESHOLD_PERCENT = 80.0;
    public static final double WARNING_THRESHOLD_PERCENT = 40.0;
    public static final double ELEVATED_THRESHOLD_PERCENT = 15.0;

    /**
     * Calculates GC pressure over the provided sample window.
     * Returns 0.0 if fewer than 2 samples are provided (can't
     * compute a delta from a single point in time).
     */
    public static GcPressure calculate(List<HeapSample> samples) {
        if (samples.size() < 2) {
            return new GcPressure(0.0, 0, 0);
        }

        HeapSample oldest = samples.get(0);
        HeapSample newest = samples.get(samples.size() - 1);

        long wallClockMs = Duration.between(oldest.timestamp(), newest.timestamp()).toMillis();
        if (wallClockMs <= 0) {
            return new GcPressure(0.0, 0, 0);
        }

        long gcTimeDeltaMs = newest.gcTimeMs() - oldest.gcTimeMs();
        long gcCountDelta = newest.gcCount() - oldest.gcCount();

        // gcTimeDeltaMs can be negative in theory if the target JVM
        // restarted between samples (cumulative counter reset to 0)
        // guard against that producing a nonsensical negative percentage
        if (gcTimeDeltaMs < 0) {
            return new GcPressure(0.0, 0, 0);
        }

        double pressurePercent = (gcTimeDeltaMs * 100.0) / wallClockMs;

        // Cap at 100% - more than 100% GC time is mathematically
        // impossible, but floating-point imprecision or a very short
        // window can produce values just above 100
        pressurePercent = Math.min(pressurePercent, 100.0);

        return new GcPressure(pressurePercent, gcCountDelta, gcTimeDeltaMs);
    }

    public record GcPressure(
            double percentOfWallClock,
            long collectionsInWindow,
            long gcTimeMsInWindow
    ) {
        public boolean isCritical() {
            return percentOfWallClock >= CRITICAL_THRESHOLD_PERCENT;
        }

        public boolean isWarning() {
            return percentOfWallClock >= WARNING_THRESHOLD_PERCENT;
        }

        public boolean isElevated() {
            return percentOfWallClock >= ELEVATED_THRESHOLD_PERCENT;
        }

        public String summary() {
            return String.format("%.1f%% of wall-clock time in GC (%d collections)",
                    percentOfWallClock, collectionsInWindow);
        }
    }
}