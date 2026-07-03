package com.jlloc.common.protocol;
import com.jlloc.common.protocol.Command;

/**
 * Request a status snapshot of all JVMs currently monitored by the daemon.
 * No parameters, always returns the full current picture.
 * Corresponds to: jlloc status
 */
public record StatusCommand() implements Command {}