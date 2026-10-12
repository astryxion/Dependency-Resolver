package com.dependencyresolver.bootstrap;

public record GateResult(boolean proceed, String message, int exitCode) {
    public static GateResult ready() {
        return new GateResult(true, "", 0);
    }

    public static GateResult abort(String message, int exitCode) {
        return new GateResult(false, message == null ? "" : message, exitCode);
    }
}
