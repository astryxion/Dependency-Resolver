package com.dependencyresolver.dependency;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record InstalledMod(
        String modId,
        String displayName,
        MavenVersion version,
        String modLoader,
        String loaderVersion,
        Path path,
        boolean embedded,
        List<DependencyConstraint> dependencies
) {
    public InstalledMod {
        Objects.requireNonNull(modId, "modId");
        displayName = displayName == null || displayName.isBlank() ? modId : displayName;
        version = version == null ? MavenVersion.parse("0") : version;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
