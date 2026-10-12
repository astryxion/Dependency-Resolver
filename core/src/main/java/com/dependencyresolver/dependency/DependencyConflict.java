package com.dependencyresolver.dependency;

import java.util.List;

public record DependencyConflict(String modId, List<DependencyConstraint> constraints) {
    public DependencyConflict {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("No version of ").append(modId).append(" satisfies all installed mods.");
        for (DependencyConstraint constraint : constraints) {
            builder.append("\n")
                    .append(constraint.requesterModId())
                    .append(" requires ")
                    .append(constraint.rawRange());
        }
        return builder.toString();
    }
}
