package com.jlloc.daemon;

/**
 * The extracted feature set for one JVM at one point in time.
 * Produced by signal extractors (GcPressureCalculator, HeapGrowthAnalyzer,
 * etc.) and consumed by DiagnosisEngine for scoring.
 *
 * Why this exists as a separate record rather than having
 * DiagnosisEngine compute these itself:
 *
 *   1. Testability — you can construct a MemorySignal directly in a
 *      test without needing a real HeapTimeline populated with
 *      carefully crafted samples. Each extractor is also independently
 *      testable against known inputs.
 *
 *   2. Extensibility — adding a new signal (allocation rate, promotion
 *      rate, pause time) means adding a field here and a new extractor,
 *      not editing DiagnosisEngine's scoring logic which should only
 *      care about combining signals, not computing them.
 *
 *   3. Clean domain boundary — DiagnosisEngine mentions none of:
 *      JMX, GarbageCollectorMXBean, VirtualMachine, Attach API.
 *      It speaks only in domain terms. MemorySignal is the
 *      translation layer that lets that boundary hold.
 *
 * Absent signals (fields not yet extracted) use sentinel values:
 *   -1.0  for doubles  (means "not available, don't score this")
 *   -1    for ints/longs
 * Scorers must check for these and skip, not treat them as real values.
 */
public record MemorySignal(

        // --- heap usage ---
        // current heap as a fraction of max (0.0 to 1.0)
        double heapUsedRatio,

        // --- GC pressure (from GcPressureCalculator) ---
        // fraction of wall-clock time spent in GC (0.0 to 1.0)
        double gcTimeRatio,
        // GC collections per second in the observation window
        double gcFrequencyPerSecond,

        // --- heap growth (from HeapGrowthAnalyzer) ---
        // slope of the post-GC floor sequence: bytes-per-second
        // positive = floor rising (leak signal)
        // near-zero = floor stable (load or healthy)
        // negative = floor falling (recovering)
        double postGcFloorSlopePerSecond,

        // --- allocation rate (from HeapGrowthAnalyzer) ---
        // bytes allocated per second across the observation window
        // high allocation rate + stable floor = load (not leak)
        // low allocation rate + rising floor = stronger leak signal
        double allocationRateBytesPerSecond,

        // number of samples this signal was computed from —
        // used by scorers to decide if there's enough data to score
        int sampleCount

) {
    public static final double UNAVAILABLE = -1.0;

    public static MemorySignal insufficient(int sampleCount) {
        return new MemorySignal(
                UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                UNAVAILABLE, UNAVAILABLE, sampleCount
        );
    }

    public boolean hasSufficientData(int minSamples) {
        return sampleCount >= minSamples;
    }

    public boolean isGcRatioAvailable() {
        return gcTimeRatio >= 0;
    }

    public boolean isFloorSlopeAvailable() {
        return postGcFloorSlopePerSecond > UNAVAILABLE;
    }

    public boolean isAllocationRateAvailable() {
        return allocationRateBytesPerSecond >= 0;
    }
}
