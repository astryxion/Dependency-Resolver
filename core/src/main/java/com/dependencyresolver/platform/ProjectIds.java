package com.dependencyresolver.platform;

import java.util.Locale;

public final class ProjectIds {
    private ProjectIds() {
    }

    /** Turns a platform slug such as {@code bclib-forge} into the mod id inside the jar. */
    public static String modId(String slug) {
        if (slug == null || slug.isBlank()) {
            return "";
        }
        String value = slug.trim().toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{"-forge", "-fabric", "-neoforge", "-quilt"}) {
            if (value.endsWith(suffix) && value.length() > suffix.length()) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    /** Letters and digits only, so {@code multi-mob} and {@code multimob} compare equal. */
    public static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder compact = new StringBuilder();
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                compact.append(character);
            }
        }
        return compact.toString();
    }

    public static boolean same(String left, String right) {
        String compact = compact(left);
        return !compact.isEmpty() && compact.equals(compact(right));
    }
}
