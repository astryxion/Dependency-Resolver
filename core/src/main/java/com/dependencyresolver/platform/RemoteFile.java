package com.dependencyresolver.platform;

import com.dependencyresolver.dependency.MavenVersion;

public record RemoteFile(
        String modId,
        String versionRaw,
        MavenVersion version,
        String displayName,
        ModPlatform platform,
        String projectId,
        String projectSlug,
        String fileName,
        String downloadUrl,
        String sha1,
        int releaseRank,
        String published
) {
    public String label() {
        String versionText = versionRaw == null || versionRaw.isBlank() ? "unverified" : versionRaw;
        return displayName + " " + versionText;
    }
}
