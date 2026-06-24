package com.jlloc.daemon;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The daemon's single owned store of everything known about every
 * detected JVM, keyed by PID.
 *
 * Why this exists (this was a real gap in the original design): up to
 * now, JvmProcessWatcher.DetectedJvm, ProcessFingerprinter.Classification,
 * and (soon) heap stats were separate records, each held only
 * momentarily by whichever class produced them, with no single place
 * that owned "everything we currently know about PID 13291." Each
 * caller had to re-fetch or re-derive data it needed. That doesn't
 * scale once more pieces (heap monitor, budget engine, CLI, profile
 * store) all need to read and update the same per-PID picture
 * concurrently.
 *
 * The flow is now:
 *
 *   JvmProcessWatcher detects a PID
 *           |
 *           v
 *   ProcessRepository.register(detectedJvm)
 *           |
 *           v
 *   ProcessRepository.updateClassification(pid, classification)
 *   ProcessRepository.updateCapabilities(pid, capabilities)
 *   ProcessRepository.updateHeapStats(pid, heapStats)   (future)
 *           |
 *           v
 *   anything else (CLI, budget engine) reads the merged ProcessRecord
 *   for a PID, or all of them, from one place
 *
 * Thread-safety: backed by ConcurrentHashMap because the watcher
 * thread, a future heap-monitor thread, and CLI/daemon-socket request
 * threads will all touch this concurrently.
 */
public class ProcessRepository {

    private final ConcurrentHashMap<Long, ProcessRecord> records = new ConcurrentHashMap<>();

    public void register(JvmProcessWatcher.DetectedJvm jvm) {
        records.compute(jvm.pid(), (pid, existing) ->
                existing == null
                        ? new ProcessRecord(jvm, null, null, null, null, null, null, Instant.now())
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
            MemoryProfile memoryProfile,
            CheckpointInfo checkpointInfo,
            Instant lastUpdated
    ) {
        ProcessRecord withDetectedJvm(JvmProcessWatcher.DetectedJvm jvm) {
            return new ProcessRecord(jvm, classification, capabilities, heapStats,
                    diagnosis, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withClassification(ProcessFingerprinter.Classification c) {
            return new ProcessRecord(detectedJvm, c, capabilities, heapStats,
                    diagnosis, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withCapabilities(JvmCapabilities c) {
            return new ProcessRecord(detectedJvm, classification, c, heapStats,
                    diagnosis, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withHeapStats(JmxConnector.HeapStats h) {
            return new ProcessRecord(detectedJvm, classification, capabilities, h,
                    diagnosis, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withDiagnosis(DiagnosisResult d) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats,
                    d, memoryProfile, checkpointInfo, Instant.now());
        }

        ProcessRecord withMemoryProfile(MemoryProfile m) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats,
                    diagnosis, m, checkpointInfo, Instant.now());
        }

        ProcessRecord withCheckpointInfo(CheckpointInfo c) {
            return new ProcessRecord(detectedJvm, classification, capabilities, heapStats,
                    diagnosis, memoryProfile, c, Instant.now());
        }

        public long pid() {
            return detectedJvm.pid();
        }
    }
}