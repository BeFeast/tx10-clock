package com.befeast.tx10clock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, strict, dependency-free JSON reader used for external configuration.
 *
 * <p>It intentionally does <em>not</em> depend on {@code org.json} or any
 * Android class, so the whole configuration core compiles and runs under a plain
 * JVM (no Android SDK, no device) — which is what makes the parser tests
 * deterministic and runnable while the Android build stays behind its licence
 * gate.
 *
 * <p>Strictness is the point. Beyond the JSON grammar it enforces:
 * <ul>
 *   <li><b>Duplicate keys</b> in any object are rejected (many lenient parsers
 *       silently keep the last one).</li>
 *   <li><b>Nesting depth</b> is bounded so a pathological document cannot
 *       exhaust the stack.</li>
 *   <li>No trailing content is allowed after the top-level value.</li>
 * </ul>
 *
 * <p>Parsed values map to: object &rarr; {@link LinkedHashMap}, array &rarr;
 * {@link ArrayList}, string &rarr; {@link String}, integral number &rarr;
 * {@link Long}, fractional number &rarr; {@link Double}, {@code true}/{@code
 * false} &rarr; {@link Boolean}, and {@code null} &rarr; a Java {@code null}.
 */
final class Json {

    /** Maximum object/array nesting the reader will descend before rejecting. */
    static final int MAX_DEPTH = 32;

    private final String s;
    private int pos;
    private int depth;

    private Json(String s) {
        this.s = s;
    }

    /**
     * Parse a complete JSON document. Throws {@link ConfigException} with
     * {@link ConfigException.Reason#MALFORMED} for any syntax error and
     * {@link ConfigException.Reason#DUPLICATE_KEY} for a repeated object key.
     */
    static Object parse(String text) throws ConfigException {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != p.s.length()) {
            throw malformed("trailing content after JSON value");
        }
        return value;
    }

    private Object readValue() throws ConfigException {
        if (pos >= s.length()) {
            throw malformed("unexpected end of input");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
            case 'f':
                return readBoolean();
            case 'n':
                return readNull();
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw malformed("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() throws ConfigException {
        enter();
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            leave();
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw malformed("expected string key in object");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object value = readValue();
            if (object.containsKey(key)) {
                throw new ConfigException(ConfigException.Reason.DUPLICATE_KEY,
                        "duplicate key '" + key + "'");
            }
            object.put(key, value);
            skipWhitespace();
            char c = next("expected ',' or '}' in object");
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw malformed("expected ',' or '}' in object");
            }
        }
        leave();
        return object;
    }

    private List<Object> readArray() throws ConfigException {
        enter();
        expect('[');
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            leave();
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char c = next("expected ',' or ']' in array");
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw malformed("expected ',' or ']' in array");
            }
        }
        leave();
        return array;
    }

    private String readString() throws ConfigException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw malformed("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= s.length()) {
                    throw malformed("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': sb.append(readUnicodeEscape()); break;
                    default: throw malformed("invalid escape '\\" + e + "'");
                }
            } else if (c < 0x20) {
                throw malformed("unescaped control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private char readUnicodeEscape() throws ConfigException {
        if (pos + 4 > s.length()) {
            throw malformed("truncated \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char h = s.charAt(pos++);
            int digit = Character.digit(h, 16);
            if (digit < 0) {
                throw malformed("invalid \\u escape");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Boolean readBoolean() throws ConfigException {
        if (s.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw malformed("invalid literal");
    }

    private Object readNull() throws ConfigException {
        if (s.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw malformed("invalid literal");
    }

    private Object readNumber() throws ConfigException {
        int start = pos;
        boolean floating = false;
        if (peek() == '-') {
            pos++;
        }
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                floating = true;
                pos++;
            } else {
                break;
            }
        }
        String token = s.substring(start, pos);
        try {
            if (floating) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        } catch (NumberFormatException ex) {
            throw malformed("invalid number '" + token + "'");
        }
    }

    // --- low-level cursor helpers --------------------------------------------

    private void enter() throws ConfigException {
        if (++depth > MAX_DEPTH) {
            throw malformed("nesting too deep");
        }
    }

    private void leave() {
        depth--;
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() throws ConfigException {
        if (pos >= s.length()) {
            throw malformed("unexpected end of input");
        }
        return s.charAt(pos);
    }

    private char next(String message) throws ConfigException {
        if (pos >= s.length()) {
            throw malformed(message);
        }
        return s.charAt(pos++);
    }

    private void expect(char c) throws ConfigException {
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw malformed("expected '" + c + "'");
        }
        pos++;
    }

    private static ConfigException malformed(String message) {
        return new ConfigException(ConfigException.Reason.MALFORMED, message);
    }
}
