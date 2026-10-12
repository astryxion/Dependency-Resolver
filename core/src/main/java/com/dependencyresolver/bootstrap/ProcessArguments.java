package com.dependencyresolver.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ProcessArguments {
    private ProcessArguments() {
    }

    static String[] current(String agentArgs) {
        List<String> args = new ArrayList<>();
        if (agentArgs != null && agentArgs.contains("gameDir=")) {
            String value = agentArgs.substring(agentArgs.indexOf("gameDir=") + "gameDir=".length()).trim();
            int space = value.indexOf(' ');
            if (space >= 0) {
                value = value.substring(0, space);
            }
            if (!value.isBlank()) {
                System.setProperty("dependencyresolver.gameDir", value);
            }
        }
        Optional<String[]> processArgs = ProcessHandle.current().info().arguments();
        processArgs.ifPresent(values -> {
            for (String value : values) {
                args.add(value);
            }
        });
        return args.toArray(String[]::new);
    }
}
