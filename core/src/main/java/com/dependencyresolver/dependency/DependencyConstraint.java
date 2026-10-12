package com.dependencyresolver.dependency;

import java.util.Objects;

public record DependencyConstraint(
        String modId,
        String requesterModId,
        boolean mandatory,
        VersionRange range,
        String rawRange,
        Side side
) {
    public DependencyConstraint {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(requesterModId, "requesterModId");
        range = range == null ? VersionRange.any() : range;
        rawRange = rawRange == null || rawRange.isBlank() ? range.format() : rawRange;
        side = side == null ? Side.BOTH : side;
    }

    public boolean appliesTo(Side running) {
        return side.includes(running);
    }
}
