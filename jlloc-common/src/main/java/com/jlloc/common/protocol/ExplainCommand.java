package com.jlloc.common.protocol;

import com.jlloc.common.protocol.Command;

/**
 * Request a detailed diagnosis explanation for one service.
 * service: app name or PID as a string (daemon resolves either).
 * Corresponds to: jlloc explain <service>
 */
public record ExplainCommand(String service) implements Command {}