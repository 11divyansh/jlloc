package com.jlloc.daemon;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts heap-growth signals from a HeapTimeline.
 * Owns the analytics that used to live in HeapTimeline itself.
 *
 * What this computes:
 *
 *   1. Post-GC floor sequence — the memory floor after each GC cycle.
 *      This is the canonical leak signal: if GC runs but the floor
 *      keeps rising, objects are being retained across collections.
 *
 *   2. Floor slope — bytes per second the floor is growing.
 *      Computed via simple linear regression over the floor sequence,
 *      rather than just counting consecutive rises. Regression is
 *      more noise-resistant (one anomalous floor doesn't break it)
 *      and gives the scorer a continuous value to weight, not just
 *      a boolean "rising/not rising".
 *
 *   3. Allocation rate — bytes allocated per second.
 *      Computed from consecutive sample deltas: when heap usage
 *      goes UP between two samples with no GC in between, that
 *      increase is allocation. High allocation + stable floor =
 *      the app is allocating fast but GC keeps up = load, not leak.
 *      Low allocation + rising floor = objects are accumulating
 *      without new allocation to explain them = stronger leak signal.
 *
 * This class knows JVM semantics (what a GC cycle boundary means,
 * what allocation means). HeapTimeline does not.
 */
public class HeapGrowthAnalyzer {

    /**
     * Extracts the post-GC floor sequence from a sample list.
     * We detect GC boundaries by watching for gcCount increments
     * between consecutive samples (coarse but accurate for 5s polling).
     */
    public List<PostGcFloor> extractFloors(List<HeapSample> samples) {
        List<PostGcFloor> floors = new ArrayList<>();
        if (samples.size() < 2) return floors;

        long windowMin = samples.get(0).usedBytes();
        long lastGcCount = samples.get(0).gcCount();
        HeapSample windowStart = samples.get(0);

        for (int i = 1; i < samples.size(); i++) {
            HeapSample s = samples.get(i);

            if (s.gcCount() > lastGcCount) {
                floors.add(new PostGcFloor(s.timestamp(), windowMin));
                windowMin = s.usedBytes();
                lastGcCount = s.gcCount();
                windowStart = s;
            } else {
                windowMin = Math.min(windowMin, s.usedBytes());
            }
        }

        // include the open (current) window
        floors.add(new PostGcFloor(samples.get(samples.size() - 1).timestamp(), windowMin));
        return floors;
    }

    /**
     * Computes the slope of the post-GC floor sequence in bytes/second
     * using ordinary least squares linear regression.
     *
     * Why OLS over "count consecutive rises":
     *   - one anomalous floor (e.g. a temporary cache eviction that
     *     deflated the floor temporarily) breaks a streak counter but
     *     only slightly affects a regression line
     *   - OLS gives the scorer a continuous value: a slope of
     *     +500KB/s is a very different signal from +50KB/s, but
     *     both would produce the same "1 rising floor" count
     *
     * Returns 0.0 if fewer than 3 floors are available (can't fit a
     * meaningful line to fewer than 3 points).
     */
    public double computeFloorSlopeBytesPerSecond(List<PostGcFloor> floors) {
        if (floors.size() < 3) return 0.0;

        // Use seconds-since-first-floor as X, floor bytes as Y
        long t0 = floors.get(0).timestamp().toEpochMilli();
        int n = floors.size();
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;

        for (PostGcFloor floor : floors) {
            double x = (floor.timestamp().toEpochMilli() - t0) / 1000.0;
            double y = floor.usedBytes();
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
        }

        double denom = n * sumXX - sumX * sumX;
        if (Math.abs(denom) < 1e-9) return 0.0;

        return (n * sumXY - sumX * sumY) / denom;
    }

    /**
     * Computes allocation rate in bytes/second from consecutive samples.
     * We count heap increases between samples where no GC ran (gcCount
     * didn't change) as allocations. Decreases in this window are
     * ignored (could be GC mid-window or measurement noise).
     */
    public double computeAllocationRateBytesPerSecond(List<HeapSample> samples) {
        if (samples.size() < 2) return 0.0;

        long totalAllocated = 0;
        HeapSample first = samples.get(0);
        HeapSample last = samples.get(samples.size() - 1);

        for (int i = 1; i < samples.size(); i++) {
            HeapSample prev = samples.get(i - 1);
            HeapSample curr = samples.get(i);

            // Only count increases between samples where no GC ran —
            // an increase after a GC could just be post-GC normal
            // heap use resuming, not new allocation
            if (curr.gcCount() == prev.gcCount()) {
                long delta = curr.usedBytes() - prev.usedBytes();
                if (delta > 0) {
                    totalAllocated += delta;
                }
            }
        }

        double windowSeconds = Duration.between(first.timestamp(), last.timestamp()).toMillis() / 1000.0;
        if (windowSeconds < 0.001) return 0.0;
        return totalAllocated / windowSeconds;
    }

    public record PostGcFloor(java.time.Instant timestamp, long usedBytes) {
    }
}