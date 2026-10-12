package com.dependencyresolver.instance;

import com.dependencyresolver.dependency.Side;

import java.nio.file.Path;
import java.util.Locale;

public final class InstanceLocator {
    private InstanceLocator() {
    }

    public static Path locate(String[] args) {
        String property = System.getProperty("dependencyresolver.gameDir");
        if (property != null && !property.isBlank()) {
            return Path.of(property).toAbsolutePath().normalize();
        }
        Path fromArgs = argumentValue(args, "--gameDir", "--game-directory");
        if (fromArgs != null) {
            return fromArgs.toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static Side side(String[] args) {
        if (args == null) {
            return Side.CLIENT;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i].toLowerCase(Locale.ROOT);
            String next = i + 1 < args.length && args[i + 1] != null ? args[i + 1].toLowerCase(Locale.ROOT) : "";
            if ((arg.equals("--launchtarget") && next.contains("server")) || arg.contains("forgeserver")) {
                return Side.SERVER;
            }
        }
        return Side.CLIENT;
    }

    public static String minecraftVersion(String[] args) {
        StringBuilder blob = new StringBuilder();
        if (args != null) {
            for (String arg : args) {
                blob.append(arg == null ? "" : arg).append(' ');
            }
        }
        blob.append(System.getProperty("java.class.path", "")).append(' ');
        blob.append(System.getProperty("sun.java.command", ""));
        String text = blob.toString();
        if (text.contains("1.20.1")) {
            return "1.20.1";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("forge-(1\\.\\d+(?:\\.\\d+)?)").matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "1.20.1";
    }

    private static Path argumentValue(String[] args, String... names) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length - 1; i++) {
            for (String name : names) {
                if (name.equals(args[i]) && args[i + 1] != null && !args[i + 1].isBlank()) {
                    return Path.of(args[i + 1]);
                }
            }
        }
        return null;
    }
}
