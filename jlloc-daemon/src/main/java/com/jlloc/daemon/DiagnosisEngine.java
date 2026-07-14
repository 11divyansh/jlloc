package com.jlloc.daemon;

import java.time.Instant;

/**
 * Produces a DiagnosisResult from a MemorySignal.
 *
 * Two-axis model: severity is determined first, diagnosis second.
 * These are independent questions with independent answers.
 *
 * Severity scoring (determines "what to do now"):
 *   CRITICAL threshold: gcTimeRatio > 0.80 OR heapUsedRatio > 0.97
 *   WARNING threshold:  gcTimeRatio > 0.40 OR heapUsedRatio > 0.80
 *   NORMAL:             everything else
 *
 *   CRITICAL is evaluated as a hard rule, not a score —
 *   you pointed out correctly that criticality is an emergency
 *   state, not "another diagnosis competing with others."
 *   Above the threshold it always wins.
 *
 * Diagnosis scoring (determines "why this is happening"):
 *   Leak signal:  postGcFloorSlope + gcFrequency + low allocation rate
 *   Load signal:  heapUsedRatio + high allocation rate + stable floor
 *   Higher signal wins. UNKNOWN if signals are close (within 15 pts).
 *
 * Signal strengths are 0–100 scores, NOT percentages, NOT probabilities.
 * They don't sum to 100. Calling them "confidence %" was misleading.
 * We now call them "signal strength" with labeled buckets
 * (Minimal / Low / Moderate / High) for the developer-facing output.
 */
public class DiagnosisEngine {

    private static final int MIN_SAMPLES = 6;

    // Hard thresholds for severity not scores, hard rules
    private static final double CRITICAL_GC_RATIO      = 0.80;
    private static final double CRITICAL_HEAP_RATIO     = 0.97;
    private static final double WARNING_GC_RATIO        = 0.40;
    private static final double WARNING_HEAP_RATIO      = 0.80;

    // Container pressure thresholds same shape as heap thresholds,
    // just measured against the cgroup limit instead of -Xmx
    private static final double CRITICAL_CONTAINER_PRESSURE = 0.92;
    private static final double WARNING_CONTAINER_PRESSURE  = 0.80;
    // Diagnosis-level container pressure threshold. Deliberately equal
    // to WARNING_CONTAINER_PRESSURE rather than the CRITICAL one:
    // severity and diagnosis are different axes, and it's fine, good,
    // even, for the diagnosis to name "this is a host pressure issue"
    // a bit before severity escalates all the way to CRITICAL.
    private static final double DIAGNOSIS_CONTAINER_PRESSURE = 0.80;

    // Diagnosis scoring thresholds
    private static final double LEAK_SLOPE_THRESHOLD    = 50_000;   // bytes/sec
    private static final double HIGH_ALLOC_RATE         = 5_000_000; // bytes/sec
    private static final double LOW_ALLOC_RATE          = 500_000;   // bytes/sec
    private static final double STABLE_SLOPE_MAX        = 20_000;   // bytes/sec
    private static final double LEAK_HEAP_RATIO         = 0.60;
    private static final double LOAD_HEAP_RATIO         = 0.75;

    // Threshold above which LOAD is treated as "add instances", not "add heap"
    private static final double SCALE_OUT_HEAP_RATIO    = 0.85;

    // If leak and load signals are within this many points, call it UNKNOWN
    private static final int AMBIGUITY_BAND             = 15;

    public DiagnosisResult diagnose(MemorySignal signal) {
        if (!signal.hasSufficientData(MIN_SAMPLES)) {
            return new DiagnosisResult(
                    DiagnosisResult.Severity.NORMAL,
                    DiagnosisResult.Diagnosis.UNKNOWN,
                    "collecting data — " + signal.sampleCount() + "/" + MIN_SAMPLES + " samples",
                    null, // no recommendation yet, genuinely nothing to recommend, not even "collect more"
                    new DiagnosisResult.SignalStrengths(0, 0, 0, 0),
                    Instant.now()
            );
        }

        // severity first, as a hard rule
        // CRITICAL is not a score competing with others.
        // It is an emergency override. If the JVM is in GC thrash
        // or heap is effectively full, that fact is the verdict
        // regardless of what the diagnosis signals say.
        DiagnosisResult.Severity severity = determineSeverity(signal);

        // diagnosis signals
        int leakSignal = scoreLeakSignal(signal);
        int loadSignal = scoreLoadSignal(signal);
        int gcPressureSignal = scoreGcPressureSignal(signal);
        int heapUsageSignal  = (int)(signal.heapUsedRatio() * 100);

        DiagnosisResult.SignalStrengths strengths = new DiagnosisResult.SignalStrengths(
                leakSignal, loadSignal, gcPressureSignal, heapUsageSignal
        );

        DiagnosisResult.Diagnosis diagnosis = determineDiagnosis(leakSignal, loadSignal, signal);
        RecommendationId recommendationId = determineRecommendationId(severity, diagnosis, signal);
        String reason = buildReason(diagnosis, signal, strengths);

        return new DiagnosisResult(severity, diagnosis, reason, recommendationId, strengths, Instant.now());
    }

    private static DiagnosisResult.Severity determineSeverity(MemorySignal signal) {
        // CRITICAL: hard rule, no scoring
        // Swap thrashing the pod appears hung to health probes even
        // though the JVM hasn't thrown OOM. Heap metrics look fine.
        if (signal.isThrashingIndicated()) {
            return DiagnosisResult.Severity.CRITICAL;
        }

        // Container memory pressure RSS approaching cgroup limit.
        // The pod will be OOM-killed by Kubernetes regardless of heap%.
        if (signal.isContainerSignalAvailable() && signal.containerMemoryPressure() >= CRITICAL_CONTAINER_PRESSURE) {
            return DiagnosisResult.Severity.CRITICAL;
        }
        if (signal.isGcRatioAvailable() && signal.gcTimeRatio() >= CRITICAL_GC_RATIO) {
            return DiagnosisResult.Severity.CRITICAL;
        }
        if (signal.heapUsedRatio() >= CRITICAL_HEAP_RATIO) {
            return DiagnosisResult.Severity.CRITICAL;
        }

        // WARNING thresholds
        if (signal.isContainerSignalAvailable() && signal.containerMemoryPressure() >= WARNING_CONTAINER_PRESSURE) {
            return DiagnosisResult.Severity.WARNING;
        }

        // WARNING: elevated but not emergency
        if (signal.isGcRatioAvailable() && signal.gcTimeRatio() >= WARNING_GC_RATIO) {
            return DiagnosisResult.Severity.WARNING;
        }
        if (signal.heapUsedRatio() >= WARNING_HEAP_RATIO) {
            return DiagnosisResult.Severity.WARNING;
        }

        return DiagnosisResult.Severity.NORMAL;
    }

    private static DiagnosisResult.Diagnosis determineDiagnosis(int leakSignal, int loadSignal, MemorySignal signal) {
        // Host/container pressure overrides JVM-internal diagnosis
        // entirely. The JVM's own heap signals are irrelevant to "why"
        // when the actual problem is at the OS or cgroup layer
        if (signal.isThrashingIndicated() || (signal.isContainerSignalAvailable() && signal.containerMemoryPressure() >= DIAGNOSIS_CONTAINER_PRESSURE)) {
            return DiagnosisResult.Diagnosis.HOST_MEMORY_PRESSURE;
        }
        // When both signals are low, the process is simply idle/healthy.
        // UNKNOWN means "signals contradict each other", not "no signal".
        // Returning UNKNOWN for a 3.8% heap idle process is misleading.
        if (leakSignal < 15 && loadSignal < 15) {
            return DiagnosisResult.Diagnosis.HEALTHY;
        }

        // If signals are within the ambiguity band AND both are
        // meaningfully elevated, we genuinely can't distinguish
        if (Math.abs(leakSignal - loadSignal) <= AMBIGUITY_BAND) {
            return DiagnosisResult.Diagnosis.UNKNOWN;
        }
        if (leakSignal > loadSignal) {
            return DiagnosisResult.Diagnosis.LEAK;
        }
        if (loadSignal > leakSignal && signal.heapUsedRatio() > LOAD_HEAP_RATIO) {
            return DiagnosisResult.Diagnosis.LOAD;
        }
        return DiagnosisResult.Diagnosis.HEALTHY;
    }

    /**
     * The single place that decides WHICH recommendation applies.
     * RecommendationEngine trusts this ID rather than re-deriving it
     */
    private static RecommendationId determineRecommendationId(
            DiagnosisResult.Severity severity,
            DiagnosisResult.Diagnosis diagnosis,
            MemorySignal signal) {

        if (severity == DiagnosisResult.Severity.CRITICAL) {
            if (signal.isThrashingIndicated()) {
                return RecommendationId.EMERGENCY_REDUCE_FOOTPRINT;
            }
            if (signal.isContainerSignalAvailable()
                    && signal.containerMemoryPressure() >= CRITICAL_CONTAINER_PRESSURE) {
                return RecommendationId.INCREASE_CONTAINER_MEMORY;
            }
            return diagnosis == DiagnosisResult.Diagnosis.LEAK
                    ? RecommendationId.TAKE_HEAP_DUMP
                    : RecommendationId.INCREASE_XMX;
        }

        return switch (diagnosis) {
            case HEALTHY -> RecommendationId.NOTHING_REQUIRED;
            case LEAK    -> RecommendationId.TAKE_HEAP_DUMP;
            case LOAD    -> {
                boolean highAlloc = signal.isAllocationRateAvailable()
                        && signal.allocationRateBytesPerSecond() > HIGH_ALLOC_RATE;
                yield (signal.heapUsedRatio() > SCALE_OUT_HEAP_RATIO && highAlloc)
                        ? RecommendationId.SCALE_HORIZONTALLY
                        : RecommendationId.INCREASE_XMX;
            }
            case HOST_MEMORY_PRESSURE -> {
                if (signal.isContainerSignalAvailable()) {
                    boolean heapIsTooBig = signal.heapUsedRatio() < 0.60
                            && signal.containerMemoryPressure() > DIAGNOSIS_CONTAINER_PRESSURE;
                    yield heapIsTooBig
                            ? RecommendationId.DECREASE_XMX
                            : RecommendationId.INCREASE_CONTAINER_MEMORY;
                }
                yield RecommendationId.REDUCE_POD_DENSITY;
            }
            case UNKNOWN -> RecommendationId.COLLECT_MORE_SIGNALS;
        };
    }

    /**
     * Leak signal: 0–100 score.
     * Rising post-GC floor is the primary driver.
     * Low allocation rate + rising floor strengthens it
     * (objects accumulating without fresh allocation = classic leak).
     */
    private static int scoreLeakSignal(MemorySignal signal) {
        int score = 0;

        if (signal.isFloorSlopeAvailable()) {
            double slope = signal.postGcFloorSlopePerSecond();
            if (slope > LEAK_SLOPE_THRESHOLD) {
                // Proportional to slope magnitude, capped at 50 points
                score += (int) Math.min(50, (slope / (LEAK_SLOPE_THRESHOLD * 4)) * 50);
            }
        }

        if (signal.heapUsedRatio() > LEAK_HEAP_RATIO) {
            score += (int)((signal.heapUsedRatio() - LEAK_HEAP_RATIO) / (1.0 - LEAK_HEAP_RATIO) * 20);
        }

        if (signal.isAllocationRateAvailable()
                && signal.allocationRateBytesPerSecond() < LOW_ALLOC_RATE
                && signal.isFloorSlopeAvailable()
                && signal.postGcFloorSlopePerSecond() > LEAK_SLOPE_THRESHOLD) {
            // The distinguishing signal: floor rising with low allocation
            // = retained objects, not new objects
            score += 30;
        }

        return Math.min(100, score);
    }

    /**
     * Load signal: 0–100 score.
     * High allocation rate + stable floor = real work, not a leak.
     */
    private static int scoreLoadSignal(MemorySignal signal) {
        int score = 0;

        if (signal.heapUsedRatio() > LOAD_HEAP_RATIO) {
            score += (int)((signal.heapUsedRatio() - LOAD_HEAP_RATIO) / (1.0 - LOAD_HEAP_RATIO) * 30);
        }

        if (signal.isAllocationRateAvailable()
                && signal.allocationRateBytesPerSecond() > HIGH_ALLOC_RATE) {
            score += (int) Math.min(40,
                    (signal.allocationRateBytesPerSecond() / (HIGH_ALLOC_RATE * 3)) * 40);
        }

        if (signal.isFloorSlopeAvailable()
                && signal.postGcFloorSlopePerSecond() < STABLE_SLOPE_MAX) {
            // Stable floor = GC keeping up despite high heap usage
            score += 30;
        }

        return Math.min(100, score);
    }

    private static int scoreGcPressureSignal(MemorySignal signal) {
        if (!signal.isGcRatioAvailable()) return 0;
        return (int) Math.min(100, signal.gcTimeRatio() * 100);
    }

    private static String buildReason(
            DiagnosisResult.Diagnosis diagnosis,
            MemorySignal signal,
            DiagnosisResult.SignalStrengths s
    ) {
        // HOST_MEMORY_PRESSURE gets its own reason format the evidence
        // that justifies this diagnosis lives in Layer 2/3 signals, not
        // the heap/GC/leak lines used for JVM-internal diagnoses. Showing
        // the heap line anyway, but explicitly labeled, since "heap looks
        // fine" is itself an important, easily-misread part of the story.
        if (diagnosis == DiagnosisResult.Diagnosis.HOST_MEMORY_PRESSURE) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Heap ........... %.1f%% (JVM-internal — not the problem)%n",
                    signal.heapUsedRatio() * 100));

            sb.append(signal.isRssAvailable()
                    ? String.format("RSS ............ %.0f MB%n", signal.rssBytes() / (1024.0 * 1024))
                    : "RSS ............ unavailable\n");
            sb.append(signal.isContainerSignalAvailable()
                    ? String.format("Container ...... %.1f%% of limit%n", signal.containerMemoryPressure() * 100)
                    : "Container ...... not running in a container / limit unreadable\n");
            sb.append(signal.isSwapRateAvailable()
                    ? String.format("Swap in/out .... %.2f / %.2f MB/s%n",
                    signal.swapInRateBytesPerSecond() / (1024.0 * 1024),
                    signal.swapOutRateBytesPerSecond() / (1024.0 * 1024))
                    : "Swap in/out .... unavailable\n");
            sb.append(signal.isMajorFaultAvailable()
                    ? String.format("Major faults ... %.1f/sec%n", signal.majorPageFaultsPerSecond())
                    : "Major faults ... unavailable\n");
            return sb.toString().stripTrailing();
        }
        String heapLine = String.format("Heap ........... %.1f%%", signal.heapUsedRatio() * 100);

        String gcLine = signal.isGcRatioAvailable()
                ? String.format("GC Pressure .... %s (%.1f%%)",
                s.gcLabel(), signal.gcTimeRatio() * 100)
                : "GC Pressure .... unavailable";

        String allocLine = signal.isAllocationRateAvailable()
                ? String.format("Allocation ..... %s (%.1f MB/s)",
                allocationLabel(signal.allocationRateBytesPerSecond()),
                signal.allocationRateBytesPerSecond() / (1024.0 * 1024.0))
                : "Allocation ..... unavailable";

        String leakLine = String.format("Leak signal .... %s (%d/100)",
                s.leakLabel(), s.leakSignal());

        String slopeLine = signal.isFloorSlopeAvailable()
                ? String.format("Floor slope .... %+.1f KB/s",
                signal.postGcFloorSlopePerSecond() / 1024.0)
                : "Floor slope .... unavailable";

        return String.join("\n", heapLine, gcLine, allocLine, leakLine, slopeLine);
    }

    private static String allocationLabel(double bytesPerSecond) {
        double mbPerSec = bytesPerSecond / (1024.0 * 1024.0);
        if (mbPerSec > 50)  return "Very High";
        if (mbPerSec > 10)  return "High";
        if (mbPerSec > 1)   return "Moderate";
        if (mbPerSec > 0.1) return "Low";
        return "Minimal";
    }
}