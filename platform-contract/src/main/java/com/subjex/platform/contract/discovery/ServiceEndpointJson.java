package com.subjex.platform.contract.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * ServiceEndpointJson — 端点报文：登记和名单只用名字、主机、端口三个字段。
 * <p>
 * Extra fields, including a status word, are ignored on read so a list can carry plain status
 * without turning the registry port into a health document.
 * 多出来的字段，包括状态词，读的时候忽略。名单可以带上直白的状态，登记端口本身仍不是健康报告。
 */
final class ServiceEndpointJson {

    private ServiceEndpointJson() {}

    static String registration(ServiceEndpoint endpoint) {
        return "{\"serviceName\":" + quote(endpoint.serviceName())
                + ",\"host\":" + quote(endpoint.host())
                + ",\"port\":" + endpoint.port()
                + "}";
    }

    static ServiceEndpoint parseRegistration(String body) {
        Parser parser = new Parser(body);
        ServiceEndpoint endpoint = parser.object();
        parser.skipWs();
        if (!parser.done()) {
            throw new IllegalArgumentException("registration has trailing text");
        }
        return endpoint;
    }

    static List<ServiceEndpoint> parseList(String body) {
        Parser parser = new Parser(body);
        parser.skipWs();
        parser.expect('[');
        List<ServiceEndpoint> endpoints = new ArrayList<>();
        parser.skipWs();
        if (parser.peek() == ']') {
            parser.next();
        } else {
            while (true) {
                endpoints.add(parser.object());
                parser.skipWs();
                char mark = parser.next();
                if (mark == ']') {
                    break;
                }
                if (mark != ',') {
                    throw new IllegalArgumentException("registry list is broken");
                }
            }
        }
        parser.skipWs();
        if (!parser.done()) {
            throw new IllegalArgumentException("registry list has trailing text");
        }
        return List.copyOf(endpoints);
    }

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
                throw new IllegalArgumentException("registry document is missing");
            }
            this.body = body;
        }

        private ServiceEndpoint object() {
            skipWs();
            expect('{');
            String serviceName = null;
            String host = null;
            Integer port = null;
            skipWs();
            if (peek() == '}') {
                next();
            } else {
                while (true) {
                    skipWs();
                    String key = string();
                    skipWs();
                    expect(':');
                    skipWs();
                    switch (key) {
                        case "serviceName" -> serviceName = string();
                        case "host" -> host = string();
                        case "port" -> port = integer();
                        default -> skipValue();
                    }
                    skipWs();
                    char mark = next();
                    if (mark == '}') {
                        break;
                    }
                    if (mark != ',') {
                        throw new IllegalArgumentException("registry object is broken");
                    }
                }
            }
            if (serviceName == null || host == null || port == null) {
                throw new IllegalArgumentException("registry object needs serviceName, host, and port");
            }
            return new ServiceEndpoint(serviceName, host, port);
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
                throw new IllegalArgumentException("registry value is not readable");
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
                    throw new IllegalArgumentException("registry object is broken");
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
                    throw new IllegalArgumentException("registry list is broken");
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
                    throw new IllegalArgumentException("registry string is broken");
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
                    default -> throw new IllegalArgumentException("registry string escape is unknown");
                }
            }
            throw new IllegalArgumentException("registry string is broken");
        }

        private char unicode() {
            if (index + 4 > body.length()) {
                throw new IllegalArgumentException("registry string escape is unknown");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                char hex = next();
                int digit = Character.digit(hex, 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("registry string escape is unknown");
                }
                code = (code << 4) + digit;
            }
            return (char) code;
        }

        private int integer() {
            String text = numberText();
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("port is not an integer");
            }
        }

        private String numberText() {
            int start = index;
            if (peek() == '-') {
                next();
            }
            if (done() || peek() < '0' || peek() > '9') {
                throw new IllegalArgumentException("registry number is broken");
            }
            while (!done() && peek() >= '0' && peek() <= '9') {
                next();
            }
            if (!done() && peek() == '.') {
                throw new IllegalArgumentException("port is not an integer");
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
                throw new IllegalArgumentException("registry document is missing '" + wanted + "'");
            }
            index++;
        }

        private char peek() {
            if (done()) {
                throw new IllegalArgumentException("registry document ended early");
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
