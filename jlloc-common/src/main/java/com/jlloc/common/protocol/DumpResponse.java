package com.jlloc.common.protocol;

import com.jlloc.common.protocol.Response;

/** Response to DumpCommand, path to the .hprof file written to disk. */
public record DumpResponse(
        String service,
        String hprofPath,
        long dumpSizeBytes
) implements Response {
}