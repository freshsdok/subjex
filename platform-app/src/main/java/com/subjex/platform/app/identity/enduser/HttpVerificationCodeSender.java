package com.subjex.platform.app.identity.enduser;

import java.util.Objects;

/**
 * HttpVerificationCodeSender — one vendor-shaped SMS OTP template (generic HTTP POST).
 * 厂商形短信验证码模板（通用 HTTP POST）。
 * <p>
 * This class does not pick a commercial vendor. It is used only when configuration
 * explicitly selects {@code sender=http} and the endpoint plus API key are complete.
 * The JSON body includes the destination, purpose, and code; callers must not log it.
 * 本类不选定商业厂商。仅当配置显式选择 {@code sender=http} 且端点与密钥配齐时才会被选中。
 * JSON 请求体含目的地、用途与验证码；调用方不得把它写入日志。
 */
public final class HttpVerificationCodeSender implements VerificationCodeSender {

    private final VerificationCodeHttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String from;

    public HttpVerificationCodeSender(
            VerificationCodeHttpClient client, String endpoint, String apiKey, String from) {
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = requireText(endpoint, "endpoint");
        this.apiKey = requireText(apiKey, "apiKey");
        this.from = from == null || from.isBlank() ? null : from.trim();
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void send(String destination, String purpose, String code) {
        String dest = requireText(destination, "destination");
        String usedFor = requireText(purpose, "purpose");
        String value = requireText(code, "code");
        String body = jsonBody(dest, usedFor, value, from);
        try {
            client.post(endpoint, apiKey, body);
        } catch (VerificationCodeSendFailedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Drop the cause so a transport message cannot leak the code or API key.
            // 丢掉原因，避免传输层报文把验证码或密钥带进异常。
            throw new VerificationCodeSendFailedException();
        }
    }

    static String jsonBody(String destination, String purpose, String code, String from) {
        StringBuilder json = new StringBuilder(128);
        json.append("{\"destination\":").append(quote(destination));
        json.append(",\"purpose\":").append(quote(purpose));
        json.append(",\"code\":").append(quote(code));
        if (from != null && !from.isBlank()) {
            json.append(",\"from\":").append(quote(from));
        }
        json.append('}');
        return json.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    String endpoint() {
        return endpoint;
    }

    String from() {
        return from;
    }
}
