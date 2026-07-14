package com.jlloc.daemon;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Metadata about a CRaC checkpoint for one JVM process.
 * Populated by the CracManager in Phase 4. Until then,
 * ProcessRepository holds a null here.
 *
 * checkpointDir:       where the checkpoint image lives on disk
 *                      (~/.jlloc/checkpoints/<appName>/<pid>/)
 * takenAt:             when the checkpoint was taken
 * heapAtCheckpoint:    the -Xmx the JVM had when checkpointed
 * targetHeapOnRestore: the -Xmx we intend to give it on restore
 *                      (why we're doing this at all — if these are
 *                      equal, we're restoring for a different reason,
 *                      e.g. node migration in production v2)
 * restored:            whether this checkpoint has been restored yet
 *                      (a checkpoint that's been restored is stale —
 *                      if the app needs another checkpoint later, we
 *                      take a fresh one rather than restoring the
 *                      same image twice)
 */
public record CheckpointInfo(
        Path checkpointDir,
        Instant takenAt,
        long heapAtCheckpoint,
        long targetHeapOnRestore,
        boolean restored
) {
    public boolean isStale() {
        return restored;
    }

    public boolean isHeapExpansion() {
        return targetHeapOnRestore > heapAtCheckpoint;
    }
}