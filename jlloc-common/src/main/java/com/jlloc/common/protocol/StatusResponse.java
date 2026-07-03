package com.jlloc.common.protocol;

import com.jlloc.common.protocol.ProcessSummary;
import com.jlloc.common.protocol.Response;

import java.util.List;

/**
 * Response to StatusCommand. Contains everything needed to render
 * `jlloc status`. No formatting — the CLI decides layout.
 */
public record StatusResponse(
        List<ProcessSummary> processes,
        long totalRamBytes,
        long availableRamBytes,
        String daemonVersion
) implements Response {
}