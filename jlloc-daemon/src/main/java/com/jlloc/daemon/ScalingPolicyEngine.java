package com.jlloc.daemon;

/**
 * Turns a diagnosis into a stable scaling contract for Phase 6.
 *
 * jlloc does not execute scaling itself. It classifies the motion:
 * horizontal, vertical, footprint reduction, or hold.
 */
public class ScalingPolicyEngine {

    public enum ScalingAxis {
        HORIZONTAL,
        VERTICAL,
        FOOTPRINT,
        HOLD
    }

    public enum ScalingDirection {
        UP,
        DOWN,
        NONE
    }

    public record ScalingDecision(
            ScalingAxis axis,
            ScalingDirection direction,
            RecommendationId recommendationId,
            String rationale
    ) {
    }

    public ScalingDecision decide(DiagnosisResult diagnosis, MemorySignal signal, String appName) {
        if (diagnosis == null || diagnosis.recommendationId() == null) {
            return new ScalingDecision(
                    ScalingAxis.HOLD,
                    ScalingDirection.NONE,
                    RecommendationId.COLLECT_MORE_SIGNALS,
                    "Not enough signal to choose a scaling motion yet."
            );
        }

        return switch (diagnosis.recommendationId()) {
            case SCALE_HORIZONTALLY -> new ScalingDecision(
                    ScalingAxis.HORIZONTAL,
                    ScalingDirection.UP,
                    RecommendationId.SCALE_HORIZONTALLY,
                    "The service is under load and should add instances instead of heap."
            );
            case INCREASE_XMX, INCREASE_CONTAINER_MEMORY -> new ScalingDecision(
                    ScalingAxis.VERTICAL,
                    ScalingDirection.UP,
                    diagnosis.recommendationId(),
                    "The service needs more memory headroom on the vertical axis."
            );
            case DECREASE_XMX, REDUCE_POD_DENSITY, EMERGENCY_REDUCE_FOOTPRINT -> new ScalingDecision(
                    diagnosis.recommendationId() == RecommendationId.REDUCE_POD_DENSITY
                            ? ScalingAxis.HORIZONTAL
                            : ScalingAxis.VERTICAL,
                    ScalingDirection.DOWN,
                    diagnosis.recommendationId(),
                    "Reduce total JVM footprint before the host or container runs out of memory."
            );
            case NOTHING_REQUIRED, TAKE_HEAP_DUMP, COLLECT_MORE_SIGNALS, WARMING_UP,
                 ENABLE_NMT, REVIEW_GC_CONFIGURATION, PROFILE_ALLOCATIONS, INSPECT_CACHE -> new ScalingDecision(
                    ScalingAxis.HOLD,
                    ScalingDirection.NONE,
                    diagnosis.recommendationId(),
                    appName == null
                            ? "No scaling action needed."
                            : "No scaling action needed for " + appName + "."
            );
        };
    }
}
