package com.jlloc.common.protocol;

/**
 * Request a CRaC-based heap resize for one service.
 * targetHeapMb: the desired new -Xmx in megabytes.
 *               0 means "let the daemon decide based on
 *               available memory and historical profile."
 * Corresponds to: jlloc fix <service> [--heap <mb>]
 */
public record FixCommand(String service, int targetHeapMb) implements Command {
    public static FixCommand autoSize(String service) {
        return new FixCommand(service, 0);
    }
}