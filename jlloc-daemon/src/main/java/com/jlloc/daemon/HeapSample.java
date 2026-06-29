package com.jlloc.daemon;

import java.time.Instant;

/**
 * One point-in-time heap reading for a single JVM process.
 *
 * Why this is different from JmxConnector.HeapStats:
 * HeapStats is what JMX hands back raw — it's the current snapshot
 * with no time context. HeapSample wraps that snapshot with a
 * timestamp so multiple samples can be stored in HeapTimeline and
 * compared over time. Without the timestamp, "heap used 1.5GB" is
 * just a number. With it, "heap used 1.5GB at 12:10, up from 1.2GB
 * at 12:00" is the beginning of a trend.
 *
 * gcCount and gcTimeMs are CUMULATIVE totals from JVM startup, not
 * deltas. GcPressureCalculator turns consecutive samples into deltas
 * (how much GC happened *between* this sample and the last one) —
 * storing the raw cumulative values here keeps the sample itself
 * simple and lets the calculator decide what window to compute over.
 */
public record HeapSample(
        Instant timestamp,
        long usedBytes,
        long committedBytes,
        long maxBytes,
        long gcCount,
        long gcTimeMs
) {
    public static HeapSample from(JmxConnector.HeapStats stats, Instant timestamp) {
        return new HeapSample(
                timestamp,
                stats.usedBytes(),
                stats.committedBytes(),
                stats.maxBytes(),
                stats.gc().collectionCount(),
                stats.gc().collectionTimeMs()
        );
    }

    public double usedPercent() {
        if (maxBytes <= 0) return 0.0;
        return (usedBytes * 100.0) / maxBytes;
    }
}