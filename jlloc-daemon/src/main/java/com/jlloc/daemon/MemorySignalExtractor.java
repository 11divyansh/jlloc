package com.jlloc.daemon;

import java.util.List;

/**
 * Coordinates all signal extractors against a HeapTimeline and
 * assembles the result into a MemorySignal for DiagnosisEngine.
 *
 * This is the only class that knows about both HeapTimeline (raw
 * storage) and the individual extractor classes. DiagnosisEngine
 * only ever sees the finished MemorySignal, it doesn't know which
 * extractors produced which fields or how.
 */
public class MemorySignalExtractor {

    private static final int MIN_SAMPLES = 6;
    private static final int RECENT_WINDOW = 60; // last ~5 minutes at 5s polling

    private final HeapGrowthAnalyzer growthAnalyzer = new HeapGrowthAnalyzer();

    public MemorySignal extract(HeapTimeline timeline) {
        List<HeapSample> samples = timeline.recent(RECENT_WINDOW);

        if (samples.size() < MIN_SAMPLES) {
            return MemorySignal.insufficient(samples.size());
        }

        HeapSample latest = samples.get(samples.size() - 1);

        // Signal: heap usage ratio
        double heapUsedRatio = latest.maxBytes() > 0
                ? (double) latest.usedBytes() / latest.maxBytes()
                : 0.0;

        // Signal: GC pressure
        GcPressureCalculator.GcPressure pressure = GcPressureCalculator.calculate(samples);
        double gcTimeRatio = pressure.percentOfWallClock() / 100.0;

        double windowSeconds = java.time.Duration.between(
                samples.get(0).timestamp(), latest.timestamp()).toMillis() / 1000.0;
        double gcFrequency = windowSeconds > 0
                ? pressure.collectionsInWindow() / windowSeconds
                : 0.0;

        // Signal: post-GC floor slope and allocation rate
        List<HeapGrowthAnalyzer.PostGcFloor> floors = growthAnalyzer.extractFloors(samples);
        double floorSlope = growthAnalyzer.computeFloorSlopeBytesPerSecond(floors);
        double allocationRate = growthAnalyzer.computeAllocationRateBytesPerSecond(samples);

        return new MemorySignal(
                heapUsedRatio,
                gcTimeRatio,
                gcFrequency,
                floorSlope,
                allocationRate,
                samples.size()
        );
    }
}