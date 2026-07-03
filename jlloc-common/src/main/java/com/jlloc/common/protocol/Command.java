package com.jlloc.common.protocol;

import java.io.Serializable;

/**
 * Root of the command protocol.
 *
 * Sealed so the compiler knows every possible command at compile time.
 *
 * Serializable because we're using Java object serialization over
 * the local socket.
 */
public sealed interface Command extends Serializable
        permits StatusCommand,
        ExplainCommand,
        DumpCommand,
        FixCommand {
}