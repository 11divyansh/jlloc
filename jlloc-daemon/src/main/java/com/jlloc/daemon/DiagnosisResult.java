package com.jlloc.daemon;

import java.time.Instant;

/**
 * The result of analyzing one JVM's heap behaviour.
 *
 * Severity and diagnosis are now separate axes, not one enum.
 * This matters because they answer different questions:
 *
 *   Severity  → "what do I do RIGHT NOW?"
 *               NORMAL / WARNING / CRITICAL
 *               The action the developer should take.
 *               CRITICAL always wins regardless of diagnosis —
 *               an emergency is an emergency.
 *
 *   Diagnosis → "why is this happening?"
 *               HEALTHY / LOAD / LEAK / UNKNOWN
 *               The underlying cause.
 *               Informs the medium-term action (increase -Xmx for
 *               load, take a heap dump for a leak, etc.)
 *
 * A doctor analogy: a patient in cardiac arrest caused by diabetes
 * gets a severity of CRITICAL and a diagnosis of LEAK (or LOAD).
 * The doctor says "cardiac arrest — treat immediately" not
 * "interesting diabetes case." Severity is what drives the action.
 * Diagnosis explains the root cause once the emergency is handled.
 *
 * Scores are named "signal strength" not "confidence" or "%":
 *   - they are NOT probabilities (they don't sum to 100)
 *   - they are NOT certainties
 *   - they are relative signal strengths on a 0–100 scale
 *   - calling them "%" was misleading (79 + 50 = 129%?)
 *   - calling them "confidence" implied rigorous statistical basis
 *     we don't have yet
 */
public record DiagnosisResult(
        Severity severity,
        Diagnosis diagnosis,
        String reason,
        String recommendation,
        SignalStrengths signalStrengths,
        Instant since
) {
    public enum Severity {
        /** Nothing concerning. No action needed. */
        NORMAL,
        /** Elevated pressure. Worth watching. No immediate action required. */
        WARNING,
        /**
         * Emergency. OOM is imminent. Developer must act now.
         * CRITICAL always takes precedence over any diagnosis —
         * the first priority is "this process is about to die",
         * not "here is an academic classification of why."
         */
        CRITICAL
    }

    public enum Diagnosis {
        /** Heap usage and GC behaviour are normal. */
        HEALTHY,
        /**
         * High heap usage explained by genuine workload —
         * GC is running but reclaiming memory successfully.
         * Sawtooth pattern present. May need a larger -Xmx
         * if sustained, but it's not a leak.
         */
        LOAD,
        /**
         * Post-GC floor is rising over time. GC runs but
         * reclaims progressively less each cycle. Objects are
         * being retained across collections.
         */
        LEAK,
        /**
         * Signals are present but contradictory or insufficient
         * to distinguish load from leak. More data needed, or
         * manual inspection required.
         */
        UNKNOWN
    }

    /**
     * Raw signal strengths — named explicitly to avoid the
     * "79 + 50 = 129%" confusion that percentages imply.
     * These are scores on a 0–100 scale, not probabilities.
     * They are relative to each other within a signal type,
     * not across signal types.
     */
    public record SignalStrengths(
            int leakSignal,
            int loadSignal,
            int gcPressureSignal,
            int heapUsageSignal
    ) {
        public String leakLabel() { return signalLabel(leakSignal); }
        public String loadLabel() { return signalLabel(loadSignal); }
        public String gcLabel()   { return signalLabel(gcPressureSignal); }

        private static String signalLabel(int score) {
            if (score >= 75) return "High";
            if (score >= 50) return "Moderate";
            if (score >= 25) return "Low";
            return "Minimal";
        }
    }
}