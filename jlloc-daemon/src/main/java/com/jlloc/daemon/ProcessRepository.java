package com.jlloc.daemon;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The daemon's single owned store of everything known about every
 * detected JVM, keyed by PID.
 */
public class ProcessRepository {

    private final ConcurrentHashMap<Long, ProcessRecord> records = new ConcurrentHashMap<>();

    public void register(JvmProcessWatcher.DetectedJvm jvm) {
        records.compute(jvm.pid(), (pid, existing) ->
                existing == null
                        ? new ProcessRecord(jvm, null, null, null, null, null, null, null, Instant.now())
                        : existing.withDetectedJvm(jvm));
    }

    public void updateDiagnosis(long pid, DiagnosisResult diagnosis) {
        records.computeIfPresent(pid, (id, record) -> record.withDiagnosis(diagnosis));
    }

    public void updateMemoryProfile(long pid, MemoryProfile profile) {
        records.computeIfPresent(pid, (id, record) -> record.withMemoryProfile(profile));
    }

    public void updateCheckpointInfo(long pid, CheckpointInfo info) {
        records.computeIfPresent(pid, (id, record) -> record.withCheckpointInfo(info));
    }

    public void remove(long pid) {
        records.remove(pid);
    }

    public void updateClassification(long pid, ProcessFingerprinter.Classification classification) {
        records.computeIfPresent(pid, (id, record) -> record.withClassification(classification));
    }

    public void updateCapabilities(long pid, JvmCapabilities capabilities) {
        records.computeIfPresent(pid, (id, record) -> record.withCapabilities(capabilities));
    }

    public void updateHeapStats(long pid, JmxConnector.HeapStats heapStats) {
        records.computeIfPresent(pid, (id, record) -> record.withHeapStats(heapStats));
    }

    public void updateLastSignal(long pid, MemorySignal signal) {
        records.computeIfPresent(pid, (id, record) -> record.withLastSignal(signal));
    }

    public Optional<ProcessRecord> get(long pid) {
        return Optional.ofNullable(records.get(pid));
    }

    public Collection<ProcessRecord> all() {
        return records.values();
    }

    /**
     * The merged, current picture of one PID. Any field may be null
     * except detectedJvm and lastUpdated - classification, capabilities,
     * and heapStats get filled in progressively as the daemon's other
     * components do their work, rather than all being known at once.
     */
    public record ProcessRecord(
            JvmProcessWatcher.DetectedJvm detectedJvm,
            ProcessFingerprinter.Classification classification,
            JvmCapabilities capabilities,
            JmxConnector.HeapStats heapStats,
            DiagnosisResult diagnosis,
            MemorySignal lastSignal,
            MemoryProfile memoryProfile,
            CheckpointInfo checkpointInfo,
            Instant lastUpdated
    ) {
        ProcessRecord withDetectedJvm(JvmProcessWatcher.DetectedJvm jvm) {
            return new ProcessRecord(jvm, classification, capabilities, heapStats, diagnosis, lastSignal, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withClassification(ProcessFingerprinter.Classification c) {
            return new ProcessRecord(detectedJvm, c, capabilities, heapStats, diagnosis, lastSignal, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withCapabilities(JvmCapabilities c) {
            return new ProcessRecord(detectedJvm, classification, c, heapStats, diagnosis, lastSignal, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withHeapStats(JmxConnector.HeapStats h) {
            return new ProcessRecord(detectedJvm, classification, capabilities, h, diagnosis, lastSignal, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withDiagnosis(DiagnosisResult d) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats, d, lastSignal, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withMemoryProfile(MemoryProfile m) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats, diagnosis, lastSignal, m,  checkpointInfo, Instant.now());
        }

        ProcessRecord withCheckpointInfo(CheckpointInfo c) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats, diagnosis, lastSignal, memoryProfile, c, Instant.now());
        }

        ProcessRecord withLastSignal(MemorySignal s) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats, diagnosis, s, memoryProfile, checkpointInfo, Instant.now());
        }

        public long pid() {
            return detectedJvm.pid();
        }
    }
}