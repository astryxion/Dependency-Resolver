package com.dependencyresolver.platform;

public enum ModPlatform {
    CURSEFORGE("CurseForge"),
    MODRINTH("Modrinth");

    private final String displayName;

    ModPlatform(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
