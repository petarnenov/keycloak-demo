package com.gw.keycloak.userstorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny hand-rolled JSON parser for the small shapes user-service emits.
 * Avoids dragging a JSON library into the SPI JAR (Keycloak ships its own
 * Jackson, but we don't want a version clash). Handles strings, numbers,
 * booleans, null, arrays, and flat objects. NOT a general-purpose parser.
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
        this.i = 0;
    }

    static Map<String, Object> parseObject(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object o = p.readValue();
        if (!(o instanceof Map)) throw new IllegalArgumentException("not a JSON object: " + json);
        return (Map<String, Object>) o;
    }

    static List<String> parseStringArray(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object o = p.readValue();
        if (!(o instanceof List<?> l)) throw new IllegalArgumentException("not a JSON array: " + json);
        List<String> out = new ArrayList<>();
        for (Object e : l) out.add(e == null ? null : e.toString());
        return out;
    }

    private Object readValue() {
        skipWs();
        if (i >= s.length()) throw err("end of input");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBool();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            String k = readString();
            skipWs();
            expect(':');
            Object v = readValue();
            m.put(k, v);
            skipWs();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected , or }");
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            i++;
            return out;
        }
        while (true) {
            out.add(readValue());
            skipWs();
            char c = s.charAt(i++);
            if (c == ']') return out;
            if (c != ',') throw err("expected , or ]");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("bad escape");
                }
            } else {
                sb.append(c);
            }
        }
        throw err("unterminated string");
    }

    private Boolean readBool() {
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        throw err("not a bool");
    }

    private Object readNull() {
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw err("not null");
    }

    private Object readNumber() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.valueOf(n);
            long l = Long.parseLong(n);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return Integer.valueOf((int) l);
            return Long.valueOf(l);
        } catch (NumberFormatException e) {
            throw err("bad number: " + n);
        }
    }

    private void expect(char c) {
        if (peek() != c) throw err("expected " + c);
        i++;
    }

    private char peek() {
        return s.charAt(i);
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + " at " + i + " in: " + s);
    }
}
