package com.jlloc.daemon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosisEngineTest {

    private final DiagnosisEngine engine = new DiagnosisEngine();

    @Test
    void insufficientDataProducesUnknownNormal() {
        DiagnosisResult r = engine.diagnose(MemorySignal.insufficient(3));
        assertEquals(DiagnosisResult.Severity.NORMAL, r.severity());
        assertEquals(DiagnosisResult.Diagnosis.UNKNOWN, r.diagnosis());
        assertTrue(r.reason().contains("collecting data"));
        assertNull(r.recommendation());
    }

    /**
     * The exact scenario you described:
     * Heap 95%, GC 92%, floor rising, allocation low.
     * CRITICAL must win over LEAK even though leak signal is strong.
     * The patient is in cardiac arrest — treat the emergency first.
     */
    @Test
    void criticalAlwaysWinsOverLeakDiagnosis() {
        MemorySignal signal = new MemorySignal(
                0.95,     // heapUsedRatio
                0.92,     // gcTimeRatio - 92%, above CRITICAL threshold
                2.0,      // gcFrequencyPerSecond
                200_000,  // postGcFloorSlopePerSecond - rising
                500_000,  // allocationRateBytesPerSecond - low
                20
        );

        DiagnosisResult r = engine.diagnose(signal);

        assertEquals(DiagnosisResult.Severity.CRITICAL, r.severity(),
                "92% GC time must produce CRITICAL severity regardless of diagnosis");
        // Diagnosis may be LEAK or UNKNOWN - that's fine.
        // The point is severity is CRITICAL, and a recommendation exists.
        assertNotNull(r.recommendation());
        assertTrue(r.recommendation().contains("jlloc"));
    }

    @Test
    void criticalOnHeapAloneWhenGcDataUnavailable() {
        MemorySignal signal = new MemorySignal(
                0.98,                      // heapUsedRatio - above critical threshold
                MemorySignal.UNAVAILABLE,  // gcTimeRatio - not available
                0.0,
                MemorySignal.UNAVAILABLE,
                MemorySignal.UNAVAILABLE,
                20
        );

        DiagnosisResult r = engine.diagnose(signal);
        assertEquals(DiagnosisResult.Severity.CRITICAL, r.severity());
    }

    @Test
    void warningWhenHeapHighButNotCritical() {
        MemorySignal signal = new MemorySignal(
                0.85,   // above WARNING_HEAP_RATIO (0.80), below CRITICAL (0.97)
                0.10,   // GC time low
                0.2,
                5_000,
                8_000_000,
                20
        );

        DiagnosisResult r = engine.diagnose(signal);
        assertEquals(DiagnosisResult.Severity.WARNING, r.severity());
    }

    @Test
    void leakDiagnosedWhenFloorRisingWithLowAllocation() {
        MemorySignal signal = new MemorySignal(
                0.72,     // heapUsedRatio - above leak threshold
                0.15,     // gcTimeRatio - moderate
                0.8,      // gcFrequencyPerSecond
                300_000,  // postGcFloorSlopePerSecond - well above threshold
                200_000,  // allocationRateBytesPerSecond - LOW
                20
        );

        DiagnosisResult r = engine.diagnose(signal);
        assertEquals(DiagnosisResult.Diagnosis.LEAK, r.diagnosis(),
                "Rising floor + low allocation should be LEAK: " + r.reason());
    }

    @Test
    void loadDiagnosedWhenHighAllocationAndStableFloor() {
        MemorySignal signal = new MemorySignal(
                0.85,        // heapUsedRatio
                0.12,        // gcTimeRatio
                0.3,
                5_000,       // postGcFloorSlopePerSecond - stable
                20_000_000,  // allocationRateBytesPerSecond - HIGH
                20
        );

        DiagnosisResult r = engine.diagnose(signal);
        assertEquals(DiagnosisResult.Severity.WARNING, r.severity());
        assertEquals(DiagnosisResult.Diagnosis.LOAD, r.diagnosis(),
                "High alloc + stable floor should be LOAD: " + r.reason());
    }

    @Test
    void normalHealthyWhenAllSignalsQuiet() {
        MemorySignal signal = new MemorySignal(
                0.20,     // heap low
                0.01,     // GC almost nothing
                0.05,
                -500,     // floor stable/falling
                500_000,
                20
        );

        DiagnosisResult r = engine.diagnose(signal);
        assertEquals(DiagnosisResult.Severity.NORMAL, r.severity());
        assertEquals(DiagnosisResult.Diagnosis.HEALTHY, r.diagnosis());
        assertNull(r.recommendation(), "NORMAL/HEALTHY should have no recommendation");
    }

    @Test
    void signalStrengthLabelsAreNotPercentages() {
        MemorySignal signal = new MemorySignal(0.5, 0.1, 0.2, 60_000, 2_000_000, 20);
        DiagnosisResult r = engine.diagnose(signal);

        // Labels should be words not numbers, to avoid the "79+50=129%" confusion
        String leakLabel = r.signalStrengths().leakLabel();
        assertTrue(
                leakLabel.equals("Minimal") || leakLabel.equals("Low")
                        || leakLabel.equals("Moderate") || leakLabel.equals("High"),
                "Leak label should be a word: " + leakLabel
        );
    }

    @Test
    void criticalAlwaysHasARecommendation() {
        MemorySignal critical = new MemorySignal(0.98, 0.95, 3.0, 500_000, 100_000, 20);
        DiagnosisResult r = engine.diagnose(critical);

        assertEquals(DiagnosisResult.Severity.CRITICAL, r.severity());
        assertNotNull(r.recommendation(), "CRITICAL must always have a recommendation");
        assertFalse(r.recommendation().isBlank());
    }
}