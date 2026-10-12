package com.dependencyresolver.dependency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DependencyResolver {
    public Plan plan(List<InstalledMod> installed, Side side) {
        List<InstalledMod> mods = installed == null ? List.of() : installed;
        Map<String, List<DependencyConstraint>> grouped = new LinkedHashMap<>();
        for (InstalledMod mod : mods) {
            for (DependencyConstraint constraint : mod.dependencies()) {
                if (!constraint.mandatory() || !constraint.appliesTo(side) || PlatformIds.skip(constraint.modId())) {
                    continue;
                }
                grouped.computeIfAbsent(constraint.modId(), ignored -> new ArrayList<>()).add(constraint);
            }
        }

        List<Dependency> dependencies = new ArrayList<>();
        List<DependencyConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<DependencyConstraint>> entry : grouped.entrySet()) {
            VersionRange combined = VersionRange.intersectAll(entry.getValue().stream().map(DependencyConstraint::range).toList());
            List<InstalledMod> providers = mods.stream()
                    .filter(mod -> mod.modId().equals(entry.getKey()) && !mod.embedded())
                    .toList();
            boolean embeddedMatch = mods.stream()
                    .anyMatch(mod -> mod.embedded() && mod.modId().equals(entry.getKey()) && !combined.isEmpty() && combined.contains(mod.version()));
            Dependency.Status status;
            if (combined.isEmpty()) {
                status = Dependency.Status.CONFLICT;
                conflicts.add(new DependencyConflict(entry.getKey(), entry.getValue()));
            } else if (providers.isEmpty() && !embeddedMatch) {
                status = Dependency.Status.MISSING;
            } else {
                boolean topLevelMatch = providers.stream().anyMatch(mod -> combined.contains(mod.version()));
                boolean topLevelMismatch = providers.stream().anyMatch(mod -> !combined.contains(mod.version()));
                if ((topLevelMatch || embeddedMatch) && topLevelMismatch) {
                    status = Dependency.Status.DUPLICATE;
                } else if (topLevelMatch || embeddedMatch) {
                    status = Dependency.Status.SATISFIED;
                } else {
                    status = Dependency.Status.WRONG_VERSION;
                }
            }
            dependencies.add(new Dependency(entry.getKey(), entry.getValue(), combined, status, providers));
        }
        return new Plan(dependencies, conflicts, DependencyGraph.build(mods, dependencies));
    }

    public Optional<MavenVersion> selectVersion(VersionRange range, List<MavenVersion> available) {
        if (range == null || range.isEmpty() || available == null) {
            return Optional.empty();
        }
        return available.stream()
                .filter(range::contains)
                .max(Comparator.naturalOrder());
    }

    public Optional<MavenVersion> selectNewest(List<VersionRange> constraints, List<String> available) {
        VersionRange combined = VersionRange.intersectAll(constraints == null ? List.of() : constraints);
        List<MavenVersion> versions = available == null ? List.of() : available.stream().map(MavenVersion::parse).toList();
        return selectVersion(combined, versions);
    }

    public record Plan(List<Dependency> dependencies, List<DependencyConflict> conflicts, DependencyGraph graph) {
        public Plan {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public boolean needsUser() {
            return dependencies.stream().anyMatch(dependency -> dependency.status() != Dependency.Status.SATISFIED);
        }

        public List<Dependency> actionable() {
            return dependencies.stream().filter(Dependency::needsDownload).toList();
        }

        public Optional<Dependency> byId(String modId) {
            return dependencies.stream().filter(dependency -> dependency.modId().equals(modId)).findFirst();
        }
    }
}
