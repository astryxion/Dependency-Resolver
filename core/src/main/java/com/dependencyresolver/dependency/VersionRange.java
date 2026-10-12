package com.dependencyresolver.dependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maven-style version range plus the operator form used in dependency reports
 * ({@code >= 2.6.0}, {@code > 1.2.6 and < 1.2.9}).
 */
public final class VersionRange {
    private final List<Bound> alternatives;
    private final boolean any;
    private final boolean empty;

    private VersionRange(List<Bound> alternatives, boolean any, boolean empty) {
        this.alternatives = alternatives;
        this.any = any;
        this.empty = empty;
    }

    public static VersionRange any() {
        return new VersionRange(List.of(), true, false);
    }

    public static VersionRange none() {
        return new VersionRange(List.of(), false, true);
    }

    public static VersionRange exact(MavenVersion version) {
        Objects.requireNonNull(version, "version");
        return new VersionRange(List.of(new Bound(version, true, version, true)), false, false);
    }

    public static VersionRange parse(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equals("*") || raw.trim().equalsIgnoreCase("any")) {
            return any();
        }
        String spec = raw.trim();
        String[] conjunction = spec.split("(?i)\\s+and\\s+|\\s*&&\\s*");
        if (conjunction.length > 1) {
            VersionRange combined = any();
            for (String part : conjunction) {
                combined = combined.intersect(parse(part.trim()));
            }
            return combined;
        }
        if (spec.indexOf('[') >= 0 || spec.indexOf('(') >= 0) {
            return fromMaven(spec);
        }
        VersionRange operator = fromOperator(spec);
        if (operator != null) {
            return operator;
        }
        return exact(MavenVersion.parse(spec));
    }

    public static VersionRange intersectAll(List<VersionRange> ranges) {
        VersionRange combined = any();
        for (VersionRange range : ranges) {
            combined = combined.intersect(range);
        }
        return combined;
    }

    public boolean isAny() {
        return any;
    }

    public boolean isEmpty() {
        return empty;
    }

    public boolean contains(MavenVersion version) {
        Objects.requireNonNull(version, "version");
        if (any) {
            return true;
        }
        if (empty) {
            return false;
        }
        for (Bound bound : alternatives) {
            if (bound.contains(version)) {
                return true;
            }
        }
        return false;
    }

    public VersionRange intersect(VersionRange other) {
        if (other == null) {
            return this;
        }
        if (this.empty || other.empty) {
            return none();
        }
        if (this.any) {
            return other;
        }
        if (other.any) {
            return this;
        }
        List<Bound> combined = new ArrayList<>();
        for (Bound left : alternatives) {
            for (Bound right : other.alternatives) {
                Bound intersection = left.intersect(right);
                if (intersection != null) {
                    combined.add(intersection);
                }
            }
        }
        if (combined.isEmpty()) {
            return none();
        }
        return new VersionRange(List.copyOf(combined), false, false);
    }

    public String format() {
        if (any) {
            return "any version";
        }
        if (empty || alternatives.isEmpty()) {
            return "no version";
        }
        List<String> parts = new ArrayList<>();
        for (Bound bound : alternatives) {
            parts.add(bound.format());
        }
        return String.join(" or ", parts);
    }

    @Override
    public String toString() {
        return format();
    }

    private static VersionRange fromMaven(String spec) {
        List<Bound> bounds = new ArrayList<>();
        int index = 0;
        while (index < spec.length()) {
            char opener = spec.charAt(index);
            if (opener != '[' && opener != '(') {
                index++;
                continue;
            }
            int bracket = spec.indexOf(']', index + 1);
            int parenthesis = spec.indexOf(')', index + 1);
            int close;
            if (bracket < 0) {
                close = parenthesis;
            } else if (parenthesis < 0) {
                close = bracket;
            } else {
                close = Math.min(bracket, parenthesis);
            }
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed version range: " + spec);
            }
            boolean lowerInclusive = opener == '[';
            boolean upperInclusive = spec.charAt(close) == ']';
            String body = spec.substring(index + 1, close).trim();
            if (!body.contains(",")) {
                MavenVersion version = MavenVersion.parse(body);
                bounds.add(new Bound(version, true, version, true));
            } else {
                String[] parts = body.split(",", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid version range: " + spec);
                }
                String low = parts[0].trim();
                String high = parts[1].trim();
                MavenVersion lower = low.isEmpty() ? null : MavenVersion.parse(low);
                MavenVersion upper = high.isEmpty() ? null : MavenVersion.parse(high);
                bounds.add(new Bound(lower, lowerInclusive, upper, upperInclusive));
            }
            index = close + 1;
            if (index < spec.length() && spec.charAt(index) == ',') {
                index++;
            }
        }
        if (bounds.isEmpty()) {
            throw new IllegalArgumentException("Invalid version range: " + spec);
        }
        return new VersionRange(List.copyOf(bounds), false, false);
    }

    private static VersionRange fromOperator(String spec) {
        String op;
        if (spec.startsWith(">=")) {
            op = ">=";
        } else if (spec.startsWith("<=")) {
            op = "<=";
        } else if (spec.startsWith(">")) {
            op = ">";
        } else if (spec.startsWith("<")) {
            op = "<";
        } else if (spec.startsWith("==")) {
            op = "==";
        } else if (spec.startsWith("=")) {
            op = "=";
        } else {
            return null;
        }
        String remainder = spec.substring(op.length()).trim();
        if (remainder.isEmpty()) {
            return null;
        }
        MavenVersion version = MavenVersion.parse(remainder);
        return switch (op) {
            case ">=" -> new VersionRange(List.of(new Bound(version, true, null, false)), false, false);
            case ">" -> new VersionRange(List.of(new Bound(version, false, null, false)), false, false);
            case "<=" -> new VersionRange(List.of(new Bound(null, false, version, true)), false, false);
            case "<" -> new VersionRange(List.of(new Bound(null, false, version, false)), false, false);
            default -> exact(version);
        };
    }

    private record Bound(MavenVersion lower, boolean lowerInclusive, MavenVersion upper, boolean upperInclusive) {
        boolean contains(MavenVersion version) {
            if (lower != null) {
                int compared = version.compareTo(lower);
                if (lowerInclusive ? compared < 0 : compared <= 0) {
                    return false;
                }
            }
            if (upper != null) {
                int compared = version.compareTo(upper);
                if (upperInclusive ? compared > 0 : compared >= 0) {
                    return false;
                }
            }
            return true;
        }

        Bound intersect(Bound other) {
            MavenVersion low = lower;
            boolean lowInc = lowerInclusive;
            if (other.lower != null && (low == null || other.lower.compareTo(low) > 0)) {
                low = other.lower;
                lowInc = other.lowerInclusive;
            } else if (other.lower != null && low != null && other.lower.compareTo(low) == 0) {
                lowInc = lowerInclusive && other.lowerInclusive;
            }
            MavenVersion high = upper;
            boolean highInc = upperInclusive;
            if (other.upper != null && (high == null || other.upper.compareTo(high) < 0)) {
                high = other.upper;
                highInc = other.upperInclusive;
            } else if (other.upper != null && high != null && other.upper.compareTo(high) == 0) {
                highInc = upperInclusive && other.upperInclusive;
            }
            if (low != null && high != null) {
                int compared = low.compareTo(high);
                if (compared > 0 || (compared == 0 && (!lowInc || !highInc))) {
                    return null;
                }
            }
            return new Bound(low, lowInc, high, highInc);
        }

        String format() {
            boolean hasLower = lower != null;
            boolean hasUpper = upper != null;
            if (hasLower && hasUpper && lower.compareTo(upper) == 0 && lowerInclusive && upperInclusive) {
                return lower.raw();
            }
            if (hasLower && !hasUpper) {
                return lowerInclusive ? lower.raw() + "+" : "> " + lower.raw();
            }
            if (!hasLower && hasUpper) {
                return (upperInclusive ? "<= " : "< ") + upper.raw();
            }
            if (hasLower) {
                String left = (lowerInclusive ? ">= " : "> ") + lower.raw();
                String right = (upperInclusive ? "<= " : "< ") + upper.raw();
                return left + " and " + right;
            }
            return "any version";
        }
    }
}
