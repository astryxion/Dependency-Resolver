package com.dependencyresolver.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static JsonValue parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Empty JSON");
        }
        Parser parser = new Parser(text);
        JsonValue value = parser.parseValue();
        parser.skip();
        if (parser.index < parser.text.length()) {
            throw new IllegalArgumentException("Trailing JSON at " + parser.index);
        }
        return value;
    }

    public static final class JsonValue {
        private static final JsonValue NULL = new JsonValue(null);
        private final Object value;

        private JsonValue(Object value) {
            this.value = value;
        }

        public static JsonValue nul() {
            return NULL;
        }

        public boolean isNull() {
            return value == null;
        }

        public boolean isObject() {
            return value instanceof Map<?, ?>;
        }

        public boolean isArray() {
            return value instanceof List<?>;
        }

        public JsonValue get(String key) {
            if (!(value instanceof Map<?, ?> map)) {
                return NULL;
            }
            Object child = map.get(key);
            return child instanceof JsonValue json ? json : NULL;
        }

        public List<JsonValue> array() {
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<JsonValue> values = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof JsonValue json) {
                    values.add(json);
                }
            }
            return values;
        }

        public String asString() {
            if (value == null) {
                return "";
            }
            if (value instanceof String string) {
                return string;
            }
            if (value instanceof JsonNumber number) {
                return number.raw;
            }
            if (value instanceof Boolean bool) {
                return Boolean.toString(bool);
            }
            return "";
        }

        public boolean asBoolean() {
            return value instanceof Boolean bool && bool;
        }

        public long asLong() {
            if (value instanceof JsonNumber number) {
                return number.decimal.longValue();
            }
            if (value instanceof String string && !string.isBlank()) {
                return new BigDecimal(string).longValue();
            }
            return 0L;
        }

        public int asInt() {
            return (int) asLong();
        }
    }

    private static final class JsonNumber {
        private final String raw;
        private final BigDecimal decimal;

        private JsonNumber(String raw) {
            this.raw = raw;
            this.decimal = new BigDecimal(raw);
        }
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        }

        private JsonValue parseValue() {
            skip();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char current = text.charAt(index);
            if (current == '{') {
                return parseObject();
            }
            if (current == '[') {
                return parseArray();
            }
            if (current == '"') {
                return new JsonValue(parseString());
            }
            if (current == 't') {
                expect("true");
                return new JsonValue(Boolean.TRUE);
            }
            if (current == 'f') {
                expect("false");
                return new JsonValue(Boolean.FALSE);
            }
            if (current == 'n') {
                expect("null");
                return JsonValue.nul();
            }
            return parseNumber();
        }

        private JsonValue parseObject() {
            index++;
            Map<String, JsonValue> map = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return new JsonValue(map);
            }
            while (index < text.length()) {
                skip();
                if (text.charAt(index) != '"') {
                    throw new IllegalArgumentException("Expected object key at " + index);
                }
                String key = parseString();
                skip();
                if (index >= text.length() || text.charAt(index) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + index);
                }
                index++;
                map.put(key, parseValue());
                skip();
                if (peek('}')) {
                    index++;
                    return new JsonValue(map);
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + index);
                }
                index++;
            }
            throw new IllegalArgumentException("Unclosed object");
        }

        private JsonValue parseArray() {
            index++;
            List<JsonValue> values = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return new JsonValue(values);
            }
            while (index < text.length()) {
                values.add(parseValue());
                skip();
                if (peek(']')) {
                    index++;
                    return new JsonValue(values);
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + index);
                }
                index++;
            }
            throw new IllegalArgumentException("Unclosed array");
        }

        private JsonValue parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (peek('.')) {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("Expected number at " + index);
            }
            return new JsonValue(new JsonNumber(text.substring(start, index)));
        }

        private String parseString() {
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                if (index >= text.length()) {
                    break;
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("Short unicode escape");
                        }
                        int code = Integer.parseInt(text.substring(index, index + 4), 16);
                        builder.append((char) code);
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape \\" + escaped);
                }
            }
            throw new IllegalArgumentException("Unclosed string");
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected " + literal + " at " + index);
            }
            index += literal.length();
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
