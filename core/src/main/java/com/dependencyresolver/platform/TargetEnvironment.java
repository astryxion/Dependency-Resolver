package com.dependencyresolver.platform;

import com.dependencyresolver.dependency.Side;

public record TargetEnvironment(String minecraftVersion, LoaderKind loader, Side side) {
    public static TargetEnvironment forgeClient() {
        return new TargetEnvironment("1.20.1", LoaderKind.FORGE, Side.CLIENT);
    }
}
