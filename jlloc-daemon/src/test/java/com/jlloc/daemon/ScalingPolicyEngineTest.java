package com.jlloc.daemon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScalingPolicyEngineTest {

    private final ScalingPolicyEngine engine = new ScalingPolicyEngine();

    @Test
    void loadMapsToHorizontalScaleOut() {
        DiagnosisResult diagnosis = new DiagnosisResult(
                DiagnosisResult.Severity.WARNING,
                DiagnosisResult.Diagnosis.LOAD,
                "load",
                RecommendationId.SCALE_HORIZONTALLY,
                new DiagnosisResult.SignalStrengths(10, 80, 20, 70),
                java.time.Instant.now());

        ScalingPolicyEngine.ScalingDecision decision = engine.decide(diagnosis, null, "demo");

        assertEquals(ScalingPolicyEngine.ScalingAxis.HORIZONTAL, decision.axis());
        assertEquals(ScalingPolicyEngine.ScalingDirection.UP, decision.direction());
        assertEquals(RecommendationId.SCALE_HORIZONTALLY, decision.recommendationId());
    }

    @Test
    void heapPressureMapsToVerticalUp() {
        DiagnosisResult diagnosis = new DiagnosisResult(
                DiagnosisResult.Severity.WARNING,
                DiagnosisResult.Diagnosis.LOAD,
                "load",
                RecommendationId.INCREASE_XMX,
                new DiagnosisResult.SignalStrengths(10, 80, 20, 70),
                java.time.Instant.now());

        ScalingPolicyEngine.ScalingDecision decision = engine.decide(diagnosis, null, "demo");

        assertEquals(ScalingPolicyEngine.ScalingAxis.VERTICAL, decision.axis());
        assertEquals(ScalingPolicyEngine.ScalingDirection.UP, decision.direction());
        assertEquals(RecommendationId.INCREASE_XMX, decision.recommendationId());
    }

    @Test
    void decreaseHeapMapsToVerticalDown() {
        DiagnosisResult diagnosis = new DiagnosisResult(
                DiagnosisResult.Severity.WARNING,
                DiagnosisResult.Diagnosis.HOST_MEMORY_PRESSURE,
                "pressure",
                RecommendationId.DECREASE_XMX,
                new DiagnosisResult.SignalStrengths(10, 10, 20, 20),
                java.time.Instant.now());

        ScalingPolicyEngine.ScalingDecision decision = engine.decide(diagnosis, null, "demo");

        assertEquals(ScalingPolicyEngine.ScalingAxis.VERTICAL, decision.axis());
        assertEquals(ScalingPolicyEngine.ScalingDirection.DOWN, decision.direction());
        assertEquals(RecommendationId.DECREASE_XMX, decision.recommendationId());
    }
}
