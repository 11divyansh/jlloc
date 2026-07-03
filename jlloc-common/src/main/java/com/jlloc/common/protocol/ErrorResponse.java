package com.jlloc.common.protocol;

import com.jlloc.common.protocol.Response;

/**
 * Returned when the daemon cannot fulfill a command
 * service not found, attach failed, internal error, etc.
 * The CLI prints the message to stderr and exits non-zero.
 */
public record ErrorResponse(String message) implements Response {
}