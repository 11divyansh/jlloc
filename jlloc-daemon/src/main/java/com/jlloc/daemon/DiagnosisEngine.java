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

    // Hard thresholds for severity — not scores, hard rules
    private static final double CRITICAL_GC_RATIO      = 0.80;
    private static final double CRITICAL_HEAP_RATIO     = 0.97;
    private static final double WARNING_GC_RATIO        = 0.40;
    private static final double WARNING_HEAP_RATIO      = 0.80;

    // Diagnosis scoring thresholds
    private static final double LEAK_SLOPE_THRESHOLD    = 50_000;   // bytes/sec
    private static final double HIGH_ALLOC_RATE         = 5_000_000; // bytes/sec
    private static final double LOW_ALLOC_RATE          = 500_000;   // bytes/sec
    private static final double STABLE_SLOPE_MAX        = 20_000;   // bytes/sec
    private static final double LEAK_HEAP_RATIO         = 0.60;
    private static final double LOAD_HEAP_RATIO         = 0.75;

    // If leak and load signals are within this many points, call it UNKNOWN
    private static final int AMBIGUITY_BAND             = 15;

    public DiagnosisResult diagnose(MemorySignal signal) {
        if (!signal.hasSufficientData(MIN_SAMPLES)) {
            return new DiagnosisResult(
                    DiagnosisResult.Severity.NORMAL,
                    DiagnosisResult.Diagnosis.UNKNOWN,
                    "collecting data — " + signal.sampleCount() + "/" + MIN_SAMPLES + " samples",
                    null,
                    new DiagnosisResult.SignalStrengths(0, 0, 0, 0),
                    Instant.now()
            );
        }

        // --- Step 1: severity first, as a hard rule ---
        // CRITICAL is not a score competing with others.
        // It is an emergency override. If the JVM is in GC thrash
        // or heap is effectively full, that fact is the verdict
        // regardless of what the diagnosis signals say.
        DiagnosisResult.Severity severity = determineSeverity(signal);

        // --- Step 2: diagnosis signals ---
        int leakSignal = scoreLeakSignal(signal);
        int loadSignal = scoreLoadSignal(signal);
        int gcPressureSignal = scoreGcPressureSignal(signal);
        int heapUsageSignal  = (int)(signal.heapUsedRatio() * 100);

        DiagnosisResult.SignalStrengths strengths = new DiagnosisResult.SignalStrengths(
                leakSignal, loadSignal, gcPressureSignal, heapUsageSignal
        );

        DiagnosisResult.Diagnosis diagnosis = determineDiagnosis(
                leakSignal, loadSignal, signal);

        String reason = buildReason(severity, diagnosis, signal, strengths);
        String recommendation = buildRecommendation(severity, diagnosis);

        return new DiagnosisResult(severity, diagnosis, reason, recommendation, strengths, Instant.now());
    }

    private static DiagnosisResult.Severity determineSeverity(MemorySignal signal) {
        // CRITICAL: hard rule, no scoring
        if (signal.isGcRatioAvailable() && signal.gcTimeRatio() >= CRITICAL_GC_RATIO) {
            return DiagnosisResult.Severity.CRITICAL;
        }
        if (signal.heapUsedRatio() >= CRITICAL_HEAP_RATIO) {
            return DiagnosisResult.Severity.CRITICAL;
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

    private static DiagnosisResult.Diagnosis determineDiagnosis(
            int leakSignal, int loadSignal, MemorySignal signal) {
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
            DiagnosisResult.Severity severity,
            DiagnosisResult.Diagnosis diagnosis,
            MemorySignal signal,
            DiagnosisResult.SignalStrengths s
    ) {
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

    private static String buildRecommendation(
            DiagnosisResult.Severity severity,
            DiagnosisResult.Diagnosis diagnosis
    ) {
        return switch (severity) {
            case CRITICAL -> switch (diagnosis) {
                case LEAK -> "Take a heap dump immediately, then restart with more heap.\n" +
                        "Command: jlloc dump <service>";
                default   -> "OOM is imminent. Restart this service now.\n" +
                        "Command: jlloc fix <service>";
            };
            case WARNING -> switch (diagnosis) {
                case LEAK    -> "Post-GC floor is rising. Take a heap dump soon to identify retained objects.\n" +
                        "Command: jlloc dump <service>";
                case LOAD    -> "High heap usage under load. Consider increasing -Xmx if this is sustained.\n" +
                        "Command: jlloc fix <service>";
                case UNKNOWN -> "Mixed signals. Watch this service over the next few minutes.";
                default      -> null;
            };
            case NORMAL -> null;
        };
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