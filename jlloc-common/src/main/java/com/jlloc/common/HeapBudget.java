package com.jlloc.common;

/**
 * Shared across daemon, agent, and cli — the response a daemon gives
 * when a new JVM asks "what heap budget do I get?"
 */
public record HeapBudget(long maxHeapBytes, String reason) {
}