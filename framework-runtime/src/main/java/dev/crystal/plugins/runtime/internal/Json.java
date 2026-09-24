package dev.crystal.plugins.runtime.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A minimal JSON reader, enough for {@code plugin-metadata.json}: objects, arrays, strings, numbers, literals. */
public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** @return a {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean} or null */
    public static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw json.error("trailing characters");
        }
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            default:
                if (text.startsWith("true", at)) {
                    at += 4;
                    return Boolean.TRUE;
                }
                if (text.startsWith("false", at)) {
                    at += 5;
                    return Boolean.FALSE;
                }
                if (text.startsWith("null", at)) {
                    at += 4;
                    return null;
                }
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return result;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            result.put(key, value());
            space();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return result;
            }
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return result;
        }
        while (true) {
            result.add(value());
            space();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return result;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = text.charAt(at++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> out.append(e);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("unexpected character '" + text.charAt(at) + "'");
        }
        return Double.valueOf(text.substring(start, at));
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid JSON at " + at + ": " + message);
    }
}
