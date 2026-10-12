package com.dependencyresolver.dependency;

import java.util.ArrayList;
import java.util.List;

public record Dependency(
        String modId,
        List<DependencyConstraint> constraints,
        VersionRange range,
        Status status,
        List<InstalledMod> providers
) {
    public enum Status {
        SATISFIED,
        MISSING,
        WRONG_VERSION,
        DUPLICATE,
        CONFLICT
    }

    public Dependency {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        providers = providers == null ? List.of() : List.copyOf(providers);
        range = range == null ? VersionRange.any() : range;
    }

    public boolean needsDownload() {
        return status == Status.MISSING || status == Status.WRONG_VERSION;
    }

    public String requiredBy() {
        return constraints.stream()
                .map(DependencyConstraint::requesterModId)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("an installed mod");
    }

    public Dependency withRequester(String requesterModId) {
        if (requesterModId == null || requesterModId.isBlank()) {
            return this;
        }
        if (constraints.stream().anyMatch(constraint -> requesterModId.equals(constraint.requesterModId()))) {
            return this;
        }
        List<DependencyConstraint> next = new ArrayList<>(constraints);
        next.add(new DependencyConstraint(modId, requesterModId, true, VersionRange.any(), "", Side.BOTH));
        return new Dependency(modId, next, range, status, providers);
    }
}
