package com.jlloc.agent;

import java.lang.instrument.Instrumentation;

public class AgentMain {

    /**
     * Called by the JVM BEFORE the target application's main() runs,
     * when this jar is loaded via -javaagent or JAVA_TOOL_OPTIONS.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[jlloc-agent] attached at JVM startup");
        // Phase 3 will add: register with daemon over socket, report heap stats
    }

    /**
     * Called when this jar is attached to an ALREADY RUNNING JVM
     * via the Attach API (VirtualMachine.loadAgent(...)).
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[jlloc-agent] attached to running JVM");
    }
}