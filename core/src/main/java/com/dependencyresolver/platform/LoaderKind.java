package com.dependencyresolver.platform;

public enum LoaderKind {
    FORGE("forge", 1);

    private final String id;
    private final int curseForgeId;

    LoaderKind(String id, int curseForgeId) {
        this.id = id;
        this.curseForgeId = curseForgeId;
    }

    public String id() {
        return id;
    }

    public int curseForgeId() {
        return curseForgeId;
    }

    public boolean acceptsModLoader(String modLoader) {
        if (modLoader == null) {
            return false;
        }
        String normalized = modLoader.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("javafml") || normalized.equals("lowcodefml");
    }
}
