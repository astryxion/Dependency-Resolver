package com.dependencyresolver.dependency;

public enum Side {
    CLIENT,
    SERVER,
    BOTH;

    public static Side parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BOTH;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CLIENT" -> CLIENT;
            case "SERVER" -> SERVER;
            default -> BOTH;
        };
    }

    public boolean includes(Side running) {
        return this == BOTH || running == null || running == BOTH || this == running;
    }
}
