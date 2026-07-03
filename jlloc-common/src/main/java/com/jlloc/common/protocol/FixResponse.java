package com.jlloc.common.protocol;

import com.jlloc.common.protocol.Response;

/** Response to FixCommand, result of the CRaC checkpoint/restore. */
public record FixResponse(
        String service,
        boolean success,
        long previousHeapMaxBytes,
        long newHeapMaxBytes,
        long pauseMs
) implements Response {
}