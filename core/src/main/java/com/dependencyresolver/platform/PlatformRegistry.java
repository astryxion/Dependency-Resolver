package com.dependencyresolver.platform;

import com.dependencyresolver.dependency.MavenVersion;
import com.dependencyresolver.dependency.VersionRange;
import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PlatformRegistry {
    private final CurseForgeClient curseForge;
    private final ModrinthClient modrinth;

    public PlatformRegistry(CurseForgeClient curseForge, ModrinthClient modrinth) {
        this.curseForge = curseForge;
        this.modrinth = modrinth;
    }

    public Lookup lookup(String modId, VersionRange range, TargetEnvironment environment) {
        boolean curseTried = false;
        boolean modrinthTried = false;
        String detail = "";
        List<RemoteFile> matches = new ArrayList<>();
        if (curseForge != null) {
            curseTried = true;
            try {
                matches.addAll(compatible(curseForge.search(modId, environment), range));
            } catch (IOException exception) {
                detail = exception.getMessage();
                ResolverLog.error("CurseForge search failed for " + modId, exception);
                if (Network.isOffline(exception)) {
                    return new Lookup(List.of(), true, false, true, "Internet connection unavailable.");
                }
                matches.clear();
            }
        }

        boolean curseHasVerified = matches.stream().anyMatch(file -> file.version() != null && range.contains(file.version()));
        if (!curseHasVerified) {
            modrinthTried = true;
            try {
                matches.addAll(compatible(modrinth.search(modId, environment), range));
            } catch (IOException exception) {
                ResolverLog.error("Modrinth search failed for " + modId, exception);
                if (matches.isEmpty() && Network.isOffline(exception)) {
                    return new Lookup(List.of(), curseTried, true, true, "Internet connection unavailable.");
                }
                if (detail.isBlank()) {
                    detail = exception.getMessage();
                }
            }
        } else {
            ResolverLog.info("CurseForge has a compatible " + modId + " file. Modrinth will not be searched.");
            matches = new ArrayList<>(matches.stream().filter(file -> file.version() != null && range.contains(file.version())).toList());
        }

        matches.sort(Comparator
                .comparing((RemoteFile file) -> file.platform() == ModPlatform.CURSEFORGE ? 0 : 1)
                .thenComparing(file -> file.version(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(RemoteFile::releaseRank));
        if (matches.isEmpty() && detail.isBlank()) {
            detail = "No " + environment.loader().id() + " " + environment.minecraftVersion()
                    + " version of " + modId + " satisfying " + range.format() + " was found.";
        }
        ResolverLog.info("Candidate order for " + modId + ": " + matches.stream()
                .map(file -> file.platform().displayName() + " " + (file.version() == null ? file.fileName() : file.version().raw()))
                .toList());
        return new Lookup(List.copyOf(matches), curseTried, modrinthTried, false, detail);
    }

    public Optional<List<String>> requiredModIds(String modId, MavenVersion installed, TargetEnvironment environment) throws IOException {
        if (curseForge != null) {
            try {
                Optional<List<String>> required = curseForge.requiredModIds(modId, installed, environment);
                if (required.isPresent() || curseForge.configured()) {
                    return required;
                }
            } catch (IOException exception) {
                ResolverLog.error("CurseForge dependency list failed for " + modId, exception);
                if (Network.isOffline(exception)) {
                    throw exception;
                }
                if (curseForge.configured()) {
                    return Optional.empty();
                }
            }
        }
        return modrinth.requiredModIds(modId, installed, environment);
    }

    private static List<RemoteFile> compatible(List<RemoteFile> files, VersionRange range) {
        List<RemoteFile> matched = new ArrayList<>();
        List<RemoteFile> unverified = new ArrayList<>();
        for (RemoteFile file : files) {
            MavenVersion version = file.version();
            if (version == null) {
                unverified.add(file);
            } else if (range.contains(version)) {
                matched.add(file);
            } else {
                ResolverLog.debug("Rejected " + file.fileName() + " version " + version.raw() + " outside " + range.format());
            }
        }
        matched.sort(Comparator.comparing(RemoteFile::version).reversed());
        if (!matched.isEmpty()) {
            return matched;
        }
        return unverified;
    }

    public record Lookup(
            List<RemoteFile> candidates,
            boolean curseForgeTried,
            boolean modrinthTried,
            boolean offline,
            String detail
    ) {
        public Lookup {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            detail = detail == null ? "" : detail;
        }
    }
}
