package com.dependencyresolver.platform;

import com.dependencyresolver.dependency.MavenVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionGuess {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+){1,4}");

    private VersionGuess() {
    }

    public static MavenVersion fromFileName(String fileName, String minecraftVersion) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Matcher matcher = NUMBER.matcher(fileName);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            int end = matcher.end();
            if (end < fileName.length() && fileName.charAt(end) == '-') {
                int qualifier = end + 1;
                if (qualifier < fileName.length() && Character.isLetter(fileName.charAt(qualifier))) {
                    int stop = qualifier + 1;
                    while (stop < fileName.length() && (Character.isLetterOrDigit(fileName.charAt(stop)) || fileName.charAt(stop) == '.')) {
                        stop++;
                    }
                    token = token + "-" + fileName.substring(qualifier, stop);
                }
            }
            found.add(token);
        }
        String chosen = null;
        for (int i = found.size() - 1; i >= 0; i--) {
            String candidate = found.get(i);
            if (isGameVersion(candidate, minecraftVersion)) {
                continue;
            }
            chosen = candidate;
            break;
        }
        return chosen == null ? null : MavenVersion.parse(chosen.replace('_', '-'));
    }

    private static boolean isGameVersion(String candidate, String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return false;
        }
        String minor = majorMinor(minecraftVersion);
        return candidate.equals(minecraftVersion)
                || candidate.equals(minor)
                || candidate.startsWith(minecraftVersion + "-")
                || candidate.startsWith(minor + "-");
    }

    private static String majorMinor(String version) {
        int first = version.indexOf('.');
        if (first < 0) {
            return version;
        }
        int second = version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }
}
