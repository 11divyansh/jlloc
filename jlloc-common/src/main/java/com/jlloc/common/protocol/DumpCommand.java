package com.jlloc.common.protocol;

import com.jlloc.common.protocol.Command;

/**
 * Request a heap dump for one service.
 * The daemon triggers jcmd/jmap on the target PID and returns
 * the path to the resulting .hprof file.
 * Corresponds to: jlloc dump <service>
 */
public record DumpCommand(String service) implements Command { }