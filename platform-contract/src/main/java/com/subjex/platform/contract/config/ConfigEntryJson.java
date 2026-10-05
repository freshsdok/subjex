package com.subjex.platform.contract.config;

/**
 * ConfigEntryJson — 配置条目报文：键、值、来源三个字段。
 * <p>
 * Extra fields are ignored on read so older callers stay readable.
 * 多出来的字段读的时候忽略，旧调用方仍然可读。
 */
final class ConfigEntryJson {

    private ConfigEntryJson() {}

    static String document(ConfigEntry entry) {
        return "{\"key\":" + quote(entry.key())
                + ",\"value\":" + quote(entry.value())
                + ",\"source\":" + quote(entry.origin().apiWord())
                + "}";
    }

    static String overrideBody(String key, String value) {
        return "{\"key\":" + quote(key) + ",\"value\":" + quote(value) + "}";
    }

    static ConfigEntry parse(String body) {
        Parser parser = new Parser(body);
        String key = null;
        String value = null;
        String source = null;
        parser.skipWs();
        parser.expect('{');
        parser.skipWs();
        if (parser.peek() == '}') {
            parser.next();
        } else {
            while (true) {
                parser.skipWs();
                String field = parser.string();
                parser.skipWs();
                parser.expect(':');
                parser.skipWs();
                switch (field) {
                    case "key" -> key = parser.string();
                    case "value" -> value = parser.string();
                    case "source" -> source = parser.string();
                    default -> parser.skipValue();
                }
                parser.skipWs();
                char mark = parser.next();
                if (mark == '}') {
                    break;
                }
                if (mark != ',') {
                    throw new IllegalArgumentException("config entry is broken");
                }
            }
        }
        parser.skipWs();
        if (!parser.done()) {
            throw new IllegalArgumentException("config entry has trailing text");
        }
        if (key == null || value == null || source == null) {
            throw new IllegalArgumentException("config entry needs key, value, and source");
        }
        return new ConfigEntry(key, value, ConfigOrigin.fromApiWord(source));
    }

    static OverrideRequest parseOverride(String body) {
        Parser parser = new Parser(body);
        String key = null;
        String value = null;
        parser.skipWs();
        parser.expect('{');
        parser.skipWs();
        if (parser.peek() == '}') {
            parser.next();
        } else {
            while (true) {
                parser.skipWs();
                String field = parser.string();
                parser.skipWs();
                parser.expect(':');
                parser.skipWs();
                switch (field) {
                    case "key" -> key = parser.string();
                    case "value" -> value = parser.string();
                    default -> parser.skipValue();
                }
                parser.skipWs();
                char mark = parser.next();
                if (mark == '}') {
                    break;
                }
                if (mark != ',') {
                    throw new IllegalArgumentException("config override is broken");
                }
            }
        }
        parser.skipWs();
        if (!parser.done()) {
            throw new IllegalArgumentException("config override has trailing text");
        }
        if (key == null || value == null) {
            throw new IllegalArgumentException("config override needs key and value");
        }
        return new OverrideRequest(key, value);
    }

    record OverrideRequest(String key, String value) {}

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static final class Parser {
        private final String body;
        private int index;

        private Parser(String body) {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("config document is missing");
            }
            this.body = body;
        }

        private void skipValue() {
            char c = peek();
            if (c == '"') {
                string();
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                numberText();
            } else if (c == '{') {
                skipObject();
            } else if (c == '[') {
                skipArray();
            } else if (body.startsWith("true", index) || body.startsWith("false", index) || body.startsWith("null", index)) {
                if (body.startsWith("true", index)) {
                    index += 4;
                } else if (body.startsWith("false", index)) {
                    index += 5;
                } else {
                    index += 4;
                }
            } else {
                throw new IllegalArgumentException("config value is not readable");
            }
        }

        private void skipObject() {
            expect('{');
            skipWs();
            if (peek() == '}') {
                next();
                return;
            }
            while (true) {
                skipWs();
                string();
                skipWs();
                expect(':');
                skipWs();
                skipValue();
                skipWs();
                char mark = next();
                if (mark == '}') {
                    return;
                }
                if (mark != ',') {
                    throw new IllegalArgumentException("config object is broken");
                }
            }
        }

        private void skipArray() {
            expect('[');
            skipWs();
            if (peek() == ']') {
                next();
                return;
            }
            while (true) {
                skipWs();
                skipValue();
                skipWs();
                char mark = next();
                if (mark == ']') {
                    return;
                }
                if (mark != ',') {
                    throw new IllegalArgumentException("config array is broken");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!done()) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (done()) {
                    throw new IllegalArgumentException("config string is broken");
                }
                char escaped = next();
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw new IllegalArgumentException("config string escape is unknown");
                }
            }
            throw new IllegalArgumentException("config string is broken");
        }

        private char unicode() {
            if (index + 4 > body.length()) {
                throw new IllegalArgumentException("config string escape is unknown");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                char hex = next();
                int digit = Character.digit(hex, 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("config string escape is unknown");
                }
                code = (code << 4) + digit;
            }
            return (char) code;
        }

        private String numberText() {
            int start = index;
            if (peek() == '-') {
                next();
            }
            if (done() || peek() < '0' || peek() > '9') {
                throw new IllegalArgumentException("config number is broken");
            }
            while (!done() && peek() >= '0' && peek() <= '9') {
                next();
            }
            return body.substring(start, index);
        }

        private void skipWs() {
            while (!done() && Character.isWhitespace(body.charAt(index))) {
                index++;
            }
        }

        private void expect(char wanted) {
            if (done() || body.charAt(index) != wanted) {
                throw new IllegalArgumentException("config document is missing '" + wanted + "'");
            }
            index++;
        }

        private char peek() {
            if (done()) {
                throw new IllegalArgumentException("config document ended early");
            }
            return body.charAt(index);
        }

        private char next() {
            char c = peek();
            index++;
            return c;
        }

        private boolean done() {
            return index >= body.length();
        }
    }
}
