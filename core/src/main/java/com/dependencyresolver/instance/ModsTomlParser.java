package com.dependencyresolver.instance;

import com.dependencyresolver.dependency.DependencyConstraint;
import com.dependencyresolver.dependency.Side;
import com.dependencyresolver.dependency.VersionRange;
import com.dependencyresolver.log.ResolverLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModsTomlParser {
    private static final Pattern MODS = Pattern.compile("\\[\\[\\s*mods\\s*]]");
    private static final Pattern DEPENDENCIES = Pattern.compile("\\[\\[\\s*dependencies\\.([A-Za-z0-9_]+)\\s*]]");

    public List<ParsedMod> parse(String text, String manifestVersion) {
        String source = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        String modLoader = "";
        String loaderVersion = "";
        Map<String, ParsedMod> mods = new LinkedHashMap<>();
        Map<String, List<DependencyConstraint>> dependencies = new LinkedHashMap<>();
        ParsedMod current = null;
        String dependencyOwner = null;
        String depModId = null;
        String depRange = null;
        boolean depMandatory = true;
        Side depSide = Side.BOTH;
        boolean inDependency = false;
        boolean multiline = false;
        StringBuilder multilineBuffer = new StringBuilder();
        char multilineQuote = 0;

        for (String rawLine : source.split("\n", -1)) {
            if (multiline) {
                int close = rawLine.indexOf(String.valueOf(multilineQuote).repeat(3));
                if (close >= 0) {
                    multiline = false;
                }
                continue;
            }
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher modsHeader = MODS.matcher(line);
            Matcher dependencyHeader = DEPENDENCIES.matcher(line);
            if (modsHeader.matches() || dependencyHeader.matches()) {
                if (inDependency && dependencyOwner != null && depModId != null) {
                    dependencies.computeIfAbsent(dependencyOwner, ignored -> new ArrayList<>())
                            .add(constraint(dependencyOwner, depModId, depMandatory, depRange, depSide));
                }
                depModId = null;
                depRange = null;
                depMandatory = true;
                depSide = Side.BOTH;
                if (modsHeader.matches()) {
                    current = new ParsedMod("", "", "", modLoader, loaderVersion, new ArrayList<>());
                    inDependency = false;
                    dependencyOwner = null;
                } else {
                    dependencyOwner = dependencyHeader.group(1);
                    inDependency = true;
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = unquote(line.substring(equals + 1).trim());
            if (value.startsWith("'''") || value.startsWith("\"\"\"")) {
                char quote = value.charAt(0);
                String marker = String.valueOf(quote).repeat(3);
                String body = value.substring(3);
                if (!body.contains(marker)) {
                    multiline = true;
                    multilineQuote = quote;
                }
                continue;
            }
            if (!inDependency && current == null) {
                if (key.equals("modLoader")) {
                    modLoader = value;
                } else if (key.equals("loaderVersion")) {
                    loaderVersion = value;
                }
                continue;
            }
            if (inDependency) {
                switch (key) {
                    case "modId" -> depModId = value;
                    case "mandatory" -> depMandatory = Boolean.parseBoolean(value);
                    case "versionRange" -> depRange = value;
                    case "side" -> depSide = Side.parse(value);
                    default -> {
                    }
                }
                continue;
            }
            switch (key) {
                case "modId" -> current.modId = value;
                case "version" -> current.version = value;
                case "displayName" -> current.displayName = value;
                default -> {
                }
            }
            if (!current.modId.isBlank() && !mods.containsKey(current.modId)) {
                current.modLoader = modLoader;
                current.loaderVersion = loaderVersion;
                mods.put(current.modId, current);
            }
        }
        if (inDependency && dependencyOwner != null && depModId != null) {
            dependencies.computeIfAbsent(dependencyOwner, ignored -> new ArrayList<>())
                    .add(constraint(dependencyOwner, depModId, depMandatory, depRange, depSide));
        }

        List<ParsedMod> parsed = new ArrayList<>();
        for (ParsedMod mod : mods.values()) {
            if ("${file.jarVersion}".equals(mod.version)) {
                mod.version = manifestVersion == null || manifestVersion.isBlank() ? "0.0.0" : manifestVersion;
            }
            if (mod.version == null || mod.version.isBlank()) {
                mod.version = manifestVersion == null || manifestVersion.isBlank() ? "0.0.0" : manifestVersion;
            }
            if (mod.displayName == null || mod.displayName.isBlank()) {
                mod.displayName = mod.modId;
            }
            mod.dependencies.addAll(dependencies.getOrDefault(mod.modId, List.of()));
            parsed.add(mod);
        }
        return parsed;
    }

    private static DependencyConstraint constraint(String owner, String modId, boolean mandatory, String rawRange, Side side) {
        VersionRange range;
        try {
            range = VersionRange.parse(rawRange);
        } catch (RuntimeException exception) {
            ResolverLog.error("Could not parse version range '" + rawRange + "' for " + modId, exception);
            range = VersionRange.any();
        }
        return new DependencyConstraint(modId, owner, mandatory, range, rawRange, side);
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (current == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (current == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                String body = value.substring(1, value.length() - 1);
                return body.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
            }
        }
        return value;
    }

    public static final class ParsedMod {
        private String modId;
        private String version;
        private String displayName;
        private String modLoader;
        private String loaderVersion;
        private final List<DependencyConstraint> dependencies;

        private ParsedMod(String modId, String version, String displayName, String modLoader, String loaderVersion,
                          List<DependencyConstraint> dependencies) {
            this.modId = modId;
            this.version = version;
            this.displayName = displayName;
            this.modLoader = modLoader;
            this.loaderVersion = loaderVersion;
            this.dependencies = dependencies;
        }

        public String modId() {
            return modId;
        }

        public String version() {
            return version;
        }

        public String displayName() {
            return displayName;
        }

        public String modLoader() {
            return modLoader;
        }

        public String loaderVersion() {
            return loaderVersion;
        }

        public List<DependencyConstraint> dependencies() {
            return dependencies;
        }
    }
}
