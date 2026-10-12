package com.dependencyresolver.dependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Orders mod versions the way Forge's Maven-style comparator does for the
 * versions mods actually publish: dotted numbers, ignored trailing zeros,
 * and pre-release qualifiers sorting before the matching release.
 */
public final class MavenVersion implements Comparable<MavenVersion> {
    private final String raw;
    private final List<Token> tokens;

    private MavenVersion(String raw, List<Token> tokens) {
        this.raw = raw;
        this.tokens = tokens;
    }

    public static MavenVersion parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            text = "0";
        }
        return new MavenVersion(text, tokenize(text));
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(MavenVersion other) {
        int shared = Math.min(tokens.size(), other.tokens.size());
        for (int i = 0; i < shared; i++) {
            int compared = tokens.get(i).compareTo(other.tokens.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        if (tokens.size() == other.tokens.size()) {
            return 0;
        }
        boolean thisLonger = tokens.size() > other.tokens.size();
        Token extra = thisLonger ? tokens.get(shared) : other.tokens.get(shared);
        int sign = thisLonger ? 1 : -1;
        if (extra.preRelease()) {
            return -sign;
        }
        return sign;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MavenVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return tokens.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }

    private static List<Token> tokenize(String raw) {
        String text = raw;
        if (text.length() > 1 && (text.charAt(0) == 'v' || text.charAt(0) == 'V') && Character.isDigit(text.charAt(1))) {
            text = text.substring(1);
        }
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '.' || current == '-' || current == '_' || current == '+') {
                index++;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < text.length() && Character.isDigit(text.charAt(end))) {
                    end++;
                }
                tokens.add(Token.number(Long.parseLong(text.substring(index, end))));
                index = end;
                continue;
            }
            if (Character.isLetter(current)) {
                int end = index + 1;
                while (end < text.length() && Character.isLetter(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(index, end).toLowerCase(Locale.ROOT);
                if (!word.equals("ga") && !word.equals("final") && !word.equals("release")) {
                    tokens.add(Token.word(word));
                }
                index = end;
                continue;
            }
            index++;
        }
        int firstWord = 0;
        while (firstWord < tokens.size() && tokens.get(firstWord).number()) {
            firstWord++;
        }
        int endNumbers = firstWord;
        while (endNumbers > 0 && tokens.get(endNumbers - 1).zero()) {
            endNumbers--;
        }
        List<Token> normalized = new ArrayList<>(tokens.size());
        normalized.addAll(tokens.subList(0, endNumbers));
        normalized.addAll(tokens.subList(firstWord, tokens.size()));
        if (normalized.isEmpty()) {
            normalized.add(Token.number(0));
        }
        return List.copyOf(normalized);
    }

    private static final class Token implements Comparable<Token> {
        private final boolean number;
        private final long value;
        private final String word;
        private final int weight;

        private Token(boolean number, long value, String word, int weight) {
            this.number = number;
            this.value = value;
            this.word = word;
            this.weight = weight;
        }

        static Token number(long value) {
            return new Token(true, value, "", 10);
        }

        static Token word(String word) {
            return new Token(false, 0, word, weight(word));
        }

        boolean number() {
            return number;
        }

        boolean zero() {
            return number && value == 0;
        }

        boolean preRelease() {
            return weight < 10;
        }

        @Override
        public int compareTo(Token other) {
            int compared = Integer.compare(weight, other.weight);
            if (compared != 0) {
                return compared;
            }
            if (number && other.number) {
                return Long.compare(value, other.value);
            }
            return word.compareTo(other.word);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Token other && compareTo(other) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(weight, number ? value : word);
        }

        private static int weight(String word) {
            return switch (word) {
                case "a", "alpha" -> 1;
                case "b", "beta" -> 2;
                case "m", "milestone" -> 3;
                case "pre", "preview" -> 4;
                case "rc", "cr" -> 5;
                case "snapshot", "snap" -> 6;
                case "sp" -> 20;
                default -> 7;
            };
        }
    }
}
