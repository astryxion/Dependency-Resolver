package com.dependencyresolver.dependency;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DependencyGraph {
    private final List<Node> roots;

    public DependencyGraph(List<Node> roots) {
        this.roots = roots == null ? List.of() : List.copyOf(roots);
    }

    public static DependencyGraph build(List<InstalledMod> installed, List<Dependency> dependencies) {
        List<InstalledMod> mods = installed == null ? List.of() : installed.stream().filter(mod -> !mod.embedded()).toList();
        Map<String, Dependency> byId = dependencies.stream()
                .collect(Collectors.toMap(Dependency::modId, Function.identity(), (left, right) -> left));
        Set<String> problems = dependencies.stream()
                .filter(dependency -> dependency.status() != Dependency.Status.SATISFIED)
                .map(Dependency::modId)
                .collect(Collectors.toSet());
        Set<String> referenced = new HashSet<>();
        for (InstalledMod mod : mods) {
            for (DependencyConstraint constraint : mod.dependencies()) {
                if (constraint.mandatory() && !PlatformIds.skip(constraint.modId())) {
                    referenced.add(constraint.modId());
                }
            }
        }
        List<Node> roots = new ArrayList<>();
        for (InstalledMod mod : mods) {
            if (referenced.contains(mod.modId())) {
                continue;
            }
            if (!leadsTo(mod.modId(), mods, dependencies, problems, new HashSet<>())) {
                continue;
            }
            roots.add(new Node(mod.modId(), mod.displayName(), byId.get(mod.modId()), children(mod, mods, dependencies, byId, problems, new HashSet<>())));
        }
        return new DependencyGraph(roots);
    }

    public List<Node> roots() {
        return roots;
    }

    public String toTreeString() {
        if (roots.isEmpty()) {
            return "(no unresolved dependencies)";
        }
        StringBuilder builder = new StringBuilder();
        for (Node root : roots) {
            write(builder, root, "", true, true);
        }
        return builder.toString().stripTrailing();
    }

    private static boolean leadsTo(String modId, List<InstalledMod> mods, List<Dependency> dependencies,
                                   Set<String> problems, Set<String> visiting) {
        if (!visiting.add(modId)) {
            return false;
        }
        if (problems.contains(modId)) {
            return true;
        }
        for (InstalledMod mod : mods) {
            if (!mod.modId().equals(modId)) {
                continue;
            }
            for (DependencyConstraint constraint : mod.dependencies()) {
                if (constraint.mandatory() && !PlatformIds.skip(constraint.modId())
                        && leadsTo(constraint.modId(), mods, dependencies, problems, visiting)) {
                    return true;
                }
            }
        }
        for (Dependency dependency : dependencies) {
            if (dependency.status() == Dependency.Status.SATISFIED || !requestedBy(dependency, modId)) {
                continue;
            }
            if (leadsTo(dependency.modId(), mods, dependencies, problems, visiting)) {
                return true;
            }
        }
        visiting.remove(modId);
        return false;
    }

    private static List<Node> children(InstalledMod mod, List<InstalledMod> mods, List<Dependency> dependencies,
                                       Map<String, Dependency> byId, Set<String> problems, Set<String> visiting) {
        visiting.add(mod.modId());
        List<Node> children = new ArrayList<>();
        for (DependencyConstraint constraint : mod.dependencies()) {
            if (!constraint.mandatory() || PlatformIds.skip(constraint.modId())) {
                continue;
            }
            if (!leadsTo(constraint.modId(), mods, dependencies, problems, new HashSet<>(visiting))) {
                continue;
            }
            children.add(node(constraint.modId(), mods, dependencies, byId, problems, new HashSet<>(visiting)));
        }
        for (Dependency dependency : dependencies) {
            if (dependency.status() == Dependency.Status.SATISFIED || !requestedBy(dependency, mod.modId())) {
                continue;
            }
            if (children.stream().anyMatch(child -> child.modId().equals(dependency.modId()))) {
                continue;
            }
            if (!leadsTo(dependency.modId(), mods, dependencies, problems, new HashSet<>(visiting))) {
                continue;
            }
            children.add(node(dependency.modId(), mods, dependencies, byId, problems, new HashSet<>(visiting)));
        }
        return List.copyOf(children);
    }

    private static boolean requestedBy(Dependency dependency, String modId) {
        return dependency.constraints().stream().anyMatch(constraint -> modId.equals(constraint.requesterModId()));
    }

    private static Node node(String modId, List<InstalledMod> mods, List<Dependency> dependencies, Map<String, Dependency> byId,
                             Set<String> problems, Set<String> visiting) {
        visiting.add(modId);
        String display = modId;
        List<Node> children = new ArrayList<>();
        for (InstalledMod mod : mods) {
            if (!mod.modId().equals(modId)) {
                continue;
            }
            display = mod.displayName();
            children.addAll(children(mod, mods, dependencies, byId, problems, visiting));
        }
        return new Node(modId, display, byId.get(modId), children);
    }

    private static void write(StringBuilder builder, Node node, String prefix, boolean last, boolean root) {
        if (root) {
            builder.append(node.displayName).append('\n');
        } else {
            builder.append(prefix).append(last ? "└── " : "├── ").append(node.displayName).append('\n');
        }
        String childPrefix = root ? "" : prefix + (last ? "    " : "│   ");
        for (int i = 0; i < node.children.size(); i++) {
            write(builder, node.children.get(i), childPrefix, i == node.children.size() - 1, false);
        }
    }

    public record Node(String modId, String displayName, Dependency dependency, List<Node> children) {
        public Node {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }
}
