package com.jlloc.daemon;

import java.time.Instant;

/**
 * Scores a MemorySignal into per-verdict confidence values.
 *
 * Why scoring instead of rules:
 *   Rules (if CRITICAL else if LEAK else ...) are hard to extend.
 *   Adding a new heuristic means editing a branching chain and
 *   reasoning about interaction with every prior branch. Five years
 *   of that produces unmaintainable code.
 *
 *   Scoring separates two concerns cleanly:
 *     - each scorer asks "how much does this signal look like X?"
 *       (0.0 = not at all, 1.0 = strongly)
 *     - the aggregator combines weighted scores into a final verdict
 *
 *   Adding a new heuristic means adding one scorer and its weight.
 *   Existing scorers don't change.
 *
 * Why probabilities instead of binary verdicts:
 *   "LEAK_SUSPECTED" sounds definitive. "leak confidence: 83%" tells
 *   the developer "we're fairly sure but not certain — look at this."
 *   Reality isn't binary: a cache warming event legitimately raises
 *   the post-GC floor (false positive for leak). A probability lets
 *   the developer decide the threshold that matters for their context.
 *
 * Current scorers and their weights:
 *
 *   CRITICAL scoring:
 *     gcTimeRatio > 0.8     → weight 10  (near-certain OOM signal)
 *     heapUsedRatio > 0.95  → weight 4
 *
 *   LEAK scoring:
 *     postGcFloorSlope +    → weight 5   (the primary leak signal)
 *     heapUsedRatio > 0.6   → weight 1
 *     low allocationRate    → weight 2   (allocating little but
 *                                         floor still rising = classic leak)
 *     gcFrequency high      → weight 2
 *
 *   LOAD scoring:
 *     heapUsedRatio > 0.75  → weight 3
 *     high allocationRate   → weight 4   (app is allocating fast)
 *     stable floor          → weight 3   (GC reclaims it each time)
 *
 * Weights are intentionally not normalized — they're relative to each
 * other within a verdict's scorer set, not across verdicts. The max
 * achievable score for each verdict is fixed, so confidence = raw
 * score / max score is always in [0.0, 1.0].
 */
public class DiagnosisEngine {

    private static final int MIN_SAMPLES = 6;

    // Thresholds — named constants rather than magic numbers inline
    private static final double CRITICAL_GC_RATIO      = 0.80;
    private static final double CRITICAL_HEAP_RATIO     = 0.95;
    private static final double LEAK_HEAP_RATIO         = 0.60;
    private static final double LOAD_HEAP_RATIO         = 0.75;
    // floor slope above this (bytes/sec) contributes to leak score
    private static final double LEAK_SLOPE_THRESHOLD    = 50_000;
    // floor slope below this is "stable" for load scoring
    private static final double STABLE_SLOPE_THRESHOLD  = 20_000;
    // allocation rate above this contributes to load score
    private static final double HIGH_ALLOC_RATE         = 5_000_000;
    // allocation rate below this strengthens leak signal (leaking
    // without allocating much = retained objects, not new allocations)
    private static final double LOW_ALLOC_RATE          = 1_000_000;

    public DiagnosisResult diagnose(MemorySignal signal) {
        if (!signal.hasSufficientData(MIN_SAMPLES)) {
            return new DiagnosisResult(
                    DiagnosisResult.Verdict.HEALTHY,
                    String.format("collecting data — %d/%d samples",
                            signal.sampleCount(), MIN_SAMPLES),
                    Instant.now()
            );
        }

        double criticalConfidence = scoreCritical(signal);
        double leakConfidence     = scoreLeak(signal);
        double loadConfidence     = scoreLoad(signal);
        double healthyConfidence  = scoreHealthy(signal);

        // Pick the verdict with the highest confidence
        double max = Math.max(Math.max(criticalConfidence, leakConfidence),
                Math.max(loadConfidence, healthyConfidence));

        DiagnosisResult.Verdict verdict;
        double winningConfidence;

        if (criticalConfidence == max) {
            verdict = DiagnosisResult.Verdict.CRITICAL;
            winningConfidence = criticalConfidence;
        } else if (leakConfidence == max) {
            verdict = DiagnosisResult.Verdict.LEAK_SUSPECTED;
            winningConfidence = leakConfidence;
        } else if (loadConfidence == max) {
            verdict = DiagnosisResult.Verdict.LOAD_PRESSURE;
            winningConfidence = loadConfidence;
        } else {
            verdict = DiagnosisResult.Verdict.HEALTHY;
            winningConfidence = healthyConfidence;
        }

        String reason = buildReason(verdict, winningConfidence, signal,
                criticalConfidence, leakConfidence, loadConfidence, healthyConfidence);

        return new DiagnosisResult(verdict, reason, Instant.now());
    }

    // scorers - each returns a confidence in [0.0, 1.0]

    private static double scoreCritical(MemorySignal s) {
        double score = 0;
        double maxScore = 14.0;

        if (s.isGcRatioAvailable()) {
            // GC consuming more than 80% of CPU is the strongest
            // single signal we have, it's the JVM's own threshold
            // for throwing GC overhead limit exceeded
            score += 10.0 * clamp((s.gcTimeRatio() - CRITICAL_GC_RATIO) / (1.0 - CRITICAL_GC_RATIO));
        }
        score += 4.0 * clamp((s.heapUsedRatio() - CRITICAL_HEAP_RATIO) / (1.0 - CRITICAL_HEAP_RATIO));

        return score / maxScore;
    }

    private static double scoreLeak(MemorySignal s) {
        double score = 0;
        double maxScore = 10.0;

        if (s.isFloorSlopeAvailable()) {
            // A rising post-GC floor is the canonical leak signal.
            // Score proportional to slope magnitude, a fast-rising
            // floor is a stronger signal than a slow one.
            double normalizedSlope = clamp(s.postGcFloorSlopePerSecond() / (LEAK_SLOPE_THRESHOLD * 4));
            score += 5.0 * normalizedSlope;
        }

        // High heap usage in combination with a rising floor = stronger
        if (s.heapUsedRatio() > LEAK_HEAP_RATIO) {
            score += 1.0 * clamp((s.heapUsedRatio() - LEAK_HEAP_RATIO) / (1.0 - LEAK_HEAP_RATIO));
        }

        if (s.isAllocationRateAvailable()) {
            // Low allocation rate + rising floor = objects are being
            // retained (leaked), not just freshly allocated. This
            // distinguishes a real leak from a cache fill.
            if (s.allocationRateBytesPerSecond() < LOW_ALLOC_RATE) {
                score += 2.0;
            }
        }

        // High GC frequency (GC running often) contributes to leak
        // score when the floor is also rising, it means GC is working
        // hard but not winning
        if (s.gcFrequencyPerSecond() > 0.5) {
            score += 2.0 * clamp(s.gcFrequencyPerSecond() / 2.0);
        }

        return score / maxScore;
    }

    private static double scoreLoad(MemorySignal s) {
        double score = 0;
        double maxScore = 10.0;

        if (s.heapUsedRatio() > LOAD_HEAP_RATIO) {
            score += 3.0 * clamp((s.heapUsedRatio() - LOAD_HEAP_RATIO) / (1.0 - LOAD_HEAP_RATIO));
        }

        if (s.isAllocationRateAvailable() && s.allocationRateBytesPerSecond() > HIGH_ALLOC_RATE) {
            // High allocation rate = app is doing real work and
            // generating objects fast. This is a load characteristic,
            // not a leak characteristic.
            score += 4.0 * clamp(s.allocationRateBytesPerSecond() / (HIGH_ALLOC_RATE * 3));
        }

        if (s.isFloorSlopeAvailable() && s.postGcFloorSlopePerSecond() < STABLE_SLOPE_THRESHOLD) {
            // Floor is stable even though heap is high — GC is keeping
            // up. This is the sawtooth pattern: classic load, not leak.
            score += 3.0 * clamp(1.0 - (s.postGcFloorSlopePerSecond() / STABLE_SLOPE_THRESHOLD));
        }

        return score / maxScore;
    }

    private static double scoreHealthy(MemorySignal s) {
        double score = 0;
        double maxScore = 6.0;

        // Low heap usage
        score += 3.0 * clamp(1.0 - s.heapUsedRatio() / LOAD_HEAP_RATIO);

        // GC not working hard
        if (s.isGcRatioAvailable()) {
            score += 3.0 * clamp(1.0 - s.gcTimeRatio() / 0.15);
        }

        return score / maxScore;
    }

    /** Clamps a value to [0.0, 1.0] */
    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String buildReason(
            DiagnosisResult.Verdict verdict,
            double confidence,
            MemorySignal signal,
            double critical, double leak, double load, double healthy
    ) {
        String slopeDesc = signal.isFloorSlopeAvailable()
                ? String.format("post-GC floor slope: %+.1f KB/s",
                signal.postGcFloorSlopePerSecond() / 1024.0)
                : "floor slope: unavailable";

        String allocDesc = signal.isAllocationRateAvailable()
                ? String.format("allocation rate: %.1f MB/s",
                signal.allocationRateBytesPerSecond() / (1024.0 * 1024.0))
                : "allocation rate: unavailable";

        String gcDesc = signal.isGcRatioAvailable()
                ? String.format("GC time: %.1f%%", signal.gcTimeRatio() * 100)
                : "GC time: unavailable";

        return String.format(
                "%s (confidence: %.0f%%)  |  heap: %.1f%%  %s  %s  %s  |  scores: critical=%.0f%% leak=%.0f%% load=%.0f%% healthy=%.0f%%",
                verdict,
                confidence * 100,
                signal.heapUsedRatio() * 100,
                gcDesc,
                slopeDesc,
                allocDesc,
                critical * 100, leak * 100, load * 100, healthy * 100
        );
    }
}