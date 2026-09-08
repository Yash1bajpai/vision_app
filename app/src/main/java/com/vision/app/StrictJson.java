package com.vision.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON parser for untrusted reasoning provider output.
 *
 * Accepts exactly one root object with string keys and string values only
 * (string-only flat schema by design: providers cannot smuggle structure the
 * validator does not expect), at most 16 unique keys, and standard JSON string
 * escapes which are decoded into the returned values.
 *
 * {@link #parseArray} additionally accepts exactly one root array whose
 * elements must each be such a flat string-only object, at most 8 elements,
 * for bounded multi-step plan proposals.
 *
 * Fail-closed: any violation of the grammar or the limits returns null instead
 * of throwing, so callers must treat null as invalid input.
 */
public final class StrictJson {
    private static final int MAX_LENGTH = 8192;
    private static final int MAX_KEYS = 16;
    private static final int MAX_ARRAY_ELEMENTS = 8;

    private final String src;
    private int pos;

    private StrictJson(String src) {
        this.src = src;
    }

    /**
     * Parses raw input as a single flat JSON object of string keys to string values.
     * Returns null for any malformed or oversized input, never throws.
     */
    public static Map<String, String> parseObject(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_LENGTH) {
            return null;
        }
        return new StrictJson(raw).parseRootObject();
    }

    /**
     * Parses raw input as a single JSON array of flat string-only objects.
     * Returns null for any malformed or oversized input, never throws.
     * An empty array parses to an empty list; plan-level validation rejects it.
     */
    public static List<Map<String, String>> parseArray(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_LENGTH) {
            return null;
        }
        return new StrictJson(raw).parseRootArray();
    }

    private List<Map<String, String>> parseRootArray() {
        if (!expect('[')) {
            return null;
        }
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        skipWs();
        if (expect(']')) {
            skipWs();
            return atEnd() ? list : null;
        }
        while (true) {
            if (list.size() >= MAX_ARRAY_ELEMENTS) {
                return null;
            }
            skipWs();
            Map<String, String> element = parseObjectValue();
            if (element == null) {
                return null;
            }
            list.add(element);
            skipWs();
            if (expect(',')) {
                continue;
            }
            if (expect(']')) {
                break;
            }
            return null;
        }
        skipWs();
        return atEnd() ? list : null;
    }

    private Map<String, String> parseRootObject() {
        Map<String, String> result = parseObjectValue();
        if (result == null) {
            return null;
        }
        skipWs();
        return atEnd() ? result : null;
    }

    private Map<String, String> parseObjectValue() {
        if (!expect('{')) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        skipWs();
        if (expect('}')) {
            return result;
        }
        while (true) {
            if (result.size() >= MAX_KEYS) {
                return null;
            }
            skipWs();
            String key = parseString();
            if (key == null) {
                return null;
            }
            skipWs();
            if (!expect(':')) {
                return null;
            }
            skipWs();
            String value = parseString();
            if (value == null) {
                return null;
            }
            if (result.containsKey(key)) {
                return null;
            }
            result.put(key, value);
            skipWs();
            if (expect(',')) {
                continue;
            }
            if (expect('}')) {
                return result;
            }
            return null;
        }
    }

    private String parseString() {
        if (!expect('"')) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                return out.toString();
            }
            if (c == '\\') {
                pos++;
                String decoded = parseEscape();
                if (decoded == null) {
                    return null;
                }
                out.append(decoded);
            } else if (c < 0x20) {
                return null;
            } else {
                out.append(c);
                pos++;
            }
        }
        return null;
    }

    private String parseEscape() {
        if (pos >= src.length()) {
            return null;
        }
        char esc = src.charAt(pos);
        pos++;
        switch (esc) {
            case '"':
                return "\"";
            case '\\':
                return "\\";
            case '/':
                return "/";
            case 'b':
                return "\b";
            case 'f':
                return "\f";
            case 'n':
                return "\n";
            case 'r':
                return "\r";
            case 't':
                return "\t";
            case 'u':
                return parseUnicodeEscape();
            default:
                return null;
        }
    }

    private String parseUnicodeEscape() {
        if (pos + 4 > src.length()) {
            return null;
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = hexValue(src.charAt(pos + i));
            if (digit < 0) {
                return null;
            }
            code = (code << 4) | digit;
        }
        pos += 4;
        if (code < 0x20) {
            return null;
        }
        if (code >= 0xD800 && code <= 0xDFFF) {
            if (code > 0xDBFF) {
                return null;
            }
            if (pos + 6 > src.length()
                    || src.charAt(pos) != '\\'
                    || src.charAt(pos + 1) != 'u') {
                return null;
            }
            int low = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hexValue(src.charAt(pos + 2 + i));
                if (digit < 0) {
                    return null;
                }
                low = (low << 4) | digit;
            }
            if (low < 0xDC00 || low > 0xDFFF) {
                return null;
            }
            pos += 6;
            return new String(Character.toChars(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)));
        }
        return String.valueOf((char) code);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean expect(char c) {
        if (pos < src.length() && src.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean atEnd() {
        return pos >= src.length();
    }
}
