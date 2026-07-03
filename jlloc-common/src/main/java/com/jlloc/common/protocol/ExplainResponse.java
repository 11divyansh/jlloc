package com.jlloc.common.protocol;

import com.jlloc.common.protocol.ProcessSummary;
import com.jlloc.common.protocol.Response;

/**
 * Response to ExplainCommand. Contains the full diagnosis picture
 * for one service. The CLI renders this as a detailed block.
 */
public record ExplainResponse(
        ProcessSummary process,
        // floor slope in bytes/sec — CLI formats as KB/s or MB/s
        double floorSlopeBytesPerSec,
        double allocationRateBytesPerSec,
        double gcTimeRatio,
        // how many samples the diagnosis is based on
        int sampleCount,
        int minSamplesRequired
) implements Response {

    public boolean hasSufficientData() {
        return sampleCount >= minSamplesRequired;
    }
}