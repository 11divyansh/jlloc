package com.jlloc.cli;

public class CliMain {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: jlloc <status|logs|profile>");
            return;
        }
        System.out.println("[jlloc-cli] command: " + args[0]);
        // Phase 2+ will add: socket client to daemon, formatted output
    }
}