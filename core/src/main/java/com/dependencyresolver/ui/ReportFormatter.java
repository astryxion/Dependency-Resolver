package com.dependencyresolver.ui;

import com.dependencyresolver.bootstrap.FlowController;
import com.dependencyresolver.dependency.Dependency;
import com.dependencyresolver.dependency.DependencyConflict;
import com.dependencyresolver.dependency.DependencyConstraint;
import com.dependencyresolver.dependency.InstalledMod;
import com.dependencyresolver.platform.RemoteFile;

import java.util.ArrayList;
import java.util.List;

public final class ReportFormatter {
    private ReportFormatter() {
    }

    public static String title(FlowController.Snapshot snapshot) {
        if (snapshot.offline() && snapshot.choices().isEmpty()) {
            return "Internet connection unavailable";
        }
        long problems = snapshot.plan().dependencies().stream().filter(dependency -> dependency.status() != Dependency.Status.SATISFIED).count();
        if (!snapshot.plan().conflicts().isEmpty() && snapshot.plan().actionable().isEmpty()) {
            return "Dependency Conflict";
        }
        if (problems > 1) {
            return "Missing Dependencies";
        }
        return "Missing Dependency";
    }

    public static String body(FlowController.Snapshot snapshot) {
        if (snapshot.offline() && snapshot.choices().isEmpty() && snapshot.plan().actionable().stream().allMatch(dependency -> snapshot.choice(dependency.modId()) == null)) {
            return "Internet connection unavailable.\n\nThe dependency could not be downloaded.";
        }
        StringBuilder builder = new StringBuilder();
        if (!snapshot.plan().conflicts().isEmpty()) {
            for (DependencyConflict conflict : snapshot.plan().conflicts()) {
                builder.append("No version of ").append(display(conflict.modId(), snapshot)).append(" satisfies all installed mods.\n\n");
                for (DependencyConstraint constraint : conflict.constraints()) {
                    builder.append(requesterName(snapshot, constraint.requesterModId()))
                            .append(" requires ")
                            .append(constraint.range().format())
                            .append("\n");
                }
                builder.append('\n');
            }
        }
        List<Dependency> downloads = snapshot.plan().actionable();
        if (downloads.size() == 1 && snapshot.plan().conflicts().isEmpty()) {
            builder.append(single(snapshot, downloads.get(0)));
            return finish(builder, snapshot);
        }
        if (!snapshot.plan().graph().roots().isEmpty()) {
            builder.append(tree(snapshot)).append("\n\n");
        }
        for (Dependency dependency : snapshot.plan().dependencies()) {
            if (dependency.status() == Dependency.Status.SATISFIED) {
                continue;
            }
            RemoteFile choice = snapshot.choice(dependency.modId());
            if (dependency.status() == Dependency.Status.CONFLICT || choice == null) {
                builder.append("✗ ").append(display(dependency.modId(), snapshot));
            } else {
                builder.append("✓ ").append(choice.displayName()).append(' ').append(choice.version() == null ? "" : choice.version().raw());
            }
            builder.append("  for ").append(requesterName(snapshot, dependency.requiredBy())).append('\n');
        }
        Dependency wrong = downloads.stream().filter(dependency -> dependency.status() == Dependency.Status.WRONG_VERSION).findFirst().orElse(null);
        if (wrong != null) {
            builder.append("\nAn incompatible version is already installed and will be moved aside, not deleted.\n");
        }
        return finish(builder, snapshot);
    }

    private static String tree(FlowController.Snapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        List<com.dependencyresolver.dependency.DependencyGraph.Node> roots = snapshot.plan().graph().roots();
        for (int i = 0; i < roots.size(); i++) {
            write(builder, snapshot, roots.get(i), "", true, true);
        }
        return builder.toString().stripTrailing();
    }

    private static void write(StringBuilder builder, FlowController.Snapshot snapshot,
                              com.dependencyresolver.dependency.DependencyGraph.Node node,
                              String prefix, boolean last, boolean root) {
        String label = display(node.modId(), snapshot);
        if (root) {
            builder.append(label).append('\n');
        } else {
            builder.append(prefix).append(last ? "└── " : "├── ").append(label).append('\n');
        }
        String childPrefix = root ? "" : prefix + (last ? "    " : "│   ");
        List<com.dependencyresolver.dependency.DependencyGraph.Node> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            write(builder, snapshot, children.get(i), childPrefix, i == children.size() - 1, false);
        }
    }

    private static String finish(StringBuilder builder, FlowController.Snapshot snapshot) {
        if (snapshot.detail() != null && !snapshot.detail().isBlank()) {
            builder.append("\n\n").append(snapshot.detail());
        }
        return builder.toString().stripTrailing();
    }

    public static String installed(List<String> lines, boolean all) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append("✓ ").append(line).append('\n');
        }
        if (all) {
            builder.append("\nAll required dependencies have been installed.\n\nMinecraft is ready to reload.");
        } else {
            builder.append("\nMinecraft is ready to reload.");
        }
        return builder.toString().stripTrailing();
    }

    public static String unavailable(Dependency dependency, String minecraftVersion, String loader) {
        return "No " + loader + " " + minecraftVersion + " version of " + dependency.modId()
                + " satisfying " + dependency.range().format() + " was found.";
    }

    private static String single(FlowController.Snapshot snapshot, Dependency dependency) {
        RemoteFile choice = snapshot.choice(dependency.modId());
        String name = choice == null ? dependency.modId() : choice.displayName();
        StringBuilder builder = new StringBuilder();
        builder.append(requesterName(snapshot, dependency.requiredBy())).append(" requires:\n\n");
        builder.append(name).append("\n\n");
        builder.append("Required version:\n").append(dependency.range().format()).append("\n\n");
        if (dependency.status() == Dependency.Status.WRONG_VERSION && !dependency.providers().isEmpty()) {
            builder.append("Installed version:\n")
                    .append(dependency.providers().get(0).version().raw())
                    .append(" (incompatible)\n\n");
        }
        if (choice != null && choice.version() != null) {
            builder.append("Compatible version found:\n").append(choice.version().raw()).append("\n\n");
            builder.append("Source:\n").append(choice.platform().displayName());
        } else {
            builder.append("No compatible file was found on CurseForge or Modrinth.");
        }
        if (dependency.status() == Dependency.Status.WRONG_VERSION) {
            builder.append("\n\nThe installed file will be moved aside, not deleted.");
        }
        return builder.toString();
    }

    private static String requesterName(FlowController.Snapshot snapshot, String requesterIds) {
        List<String> names = new ArrayList<>();
        for (String requesterId : requesterIds.split(", ")) {
            names.add(display(requesterId, snapshot));
        }
        return String.join(", ", names);
    }

    private static String display(String modId, FlowController.Snapshot snapshot) {
        for (InstalledMod mod : snapshot.installed()) {
            if (mod.modId().equals(modId) && !mod.embedded()) {
                return mod.displayName();
            }
        }
        RemoteFile choice = snapshot.choice(modId);
        if (choice != null && choice.displayName() != null && !choice.displayName().isBlank()) {
            return choice.displayName();
        }
        return modId;
    }
}
