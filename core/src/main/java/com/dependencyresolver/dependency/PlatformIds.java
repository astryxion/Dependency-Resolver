package com.dependencyresolver.dependency;

import java.util.Locale;
import java.util.Set;

/** Mod ids that belong to the loader or the game, not to a downloadable jar. */
public final class PlatformIds {
    private static final Set<String> IDS = Set.of(
            "minecraft",
            "forge",
            "javafml",
            "lowcodefml",
            "neoforge",
            "fabric",
            "fabricloader",
            "quilt_loader",
            "dependencyresolver"
    );

    private PlatformIds() {
    }

    public static boolean skip(String modId) {
        return modId != null && IDS.contains(modId.toLowerCase(Locale.ROOT));
    }
}
