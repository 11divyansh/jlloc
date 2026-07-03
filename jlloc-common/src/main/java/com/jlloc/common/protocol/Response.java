package com.jlloc.common.protocol;

import java.io.Serializable;

/**
 * Root of the response protocol.
 *
 * Every response includes a success flag and an optional error
 * message rather than throwing exceptions across the socket
 * boundary transport errors and daemon errors are both modeled
 * as ErrorResponse, not as thrown exceptions that the CLI would
 * need to catch from an ObjectInputStream.
 */
public sealed interface Response extends Serializable
        permits StatusResponse,
        ExplainResponse,
        DumpResponse,
        FixResponse,
        ErrorResponse {
}