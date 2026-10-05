package com.subjex.platform.contract.delivery;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * OutboxSocketFrame — 出箱套接字帧：一条出箱事件在两个进程之间的线路格式。
 * <p>
 * Protocol 2 authenticates with HMAC-SHA256 over a shared secret. The operator password is not on the wire.
 * platform-app writes one frame. sample-consumer reads it and answers accepted or rejected.
 * 协议 2 用共享密钥的 HMAC-SHA256 认证，帧上不再带操作员口令。
 * platform-app 写入一帧。sample-consumer 读入后回答 accepted 或 rejected。
 */
public final class OutboxSocketFrame {

    public static final String PROTOCOL_LINE = "SUBJEX-OUTBOX 2";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";
    public static final int MAX_BODY_BYTES = 4000;

    private OutboxSocketFrame() {
    }

    /**
     * Notice — 套接字上的一条出箱通知：事件名、追踪、HMAC、正文。
     */
    public record Notice(
            String eventName,
            String traceparent,
            long authTimestampMillis,
            String authSignature,
            boolean failureRequested,
            String eventBody) {
    }

    public static void writeNotice(OutputStream out, Notice notice) throws IOException {
        requireLine("event-name", notice.eventName());
        requireLine("traceparent", notice.traceparent());
        requireLine("auth-signature", notice.authSignature());
        if (notice.authTimestampMillis() < 0) {
            throw new IOException("auth-timestamp is missing");
        }
        if (notice.eventBody() == null) {
            throw new IOException("event body is missing");
        }
        byte[] body = notice.eventBody().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("event body is longer than the outbox column");
        }
        StringBuilder header = new StringBuilder();
        header.append(PROTOCOL_LINE).append('\n');
        header.append("event-name: ").append(notice.eventName()).append('\n');
        header.append("traceparent: ").append(notice.traceparent()).append('\n');
        header.append("auth-scheme: ").append(OutboxHmac.SCHEME).append('\n');
        header.append("auth-timestamp: ").append(notice.authTimestampMillis()).append('\n');
        header.append("auth-signature: ").append(notice.authSignature()).append('\n');
        header.append("failure-requested: ").append(notice.failureRequested() ? "true" : "false").append('\n');
        header.append("body-bytes: ").append(body.length).append('\n');
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    public static Notice readNotice(InputStream in) throws IOException {
        if (!PROTOCOL_LINE.equals(readLine(in))) {
            throw new IOException("unexpected outbox socket protocol");
        }
        String eventName = value(readLine(in), "event-name");
        String traceparent = value(readLine(in), "traceparent");
        String scheme = value(readLine(in), "auth-scheme");
        if (!OutboxHmac.SCHEME.equals(scheme)) {
            throw new IOException("unexpected outbox auth scheme");
        }
        long timestamp = parseTimestamp(value(readLine(in), "auth-timestamp"));
        String signature = value(readLine(in), "auth-signature");
        String failure = value(readLine(in), "failure-requested");
        if (!"true".equals(failure) && !"false".equals(failure)) {
            throw new IOException("failure-requested must be true or false");
        }
        int bodyBytes = parseBodyLength(value(readLine(in), "body-bytes"));
        byte[] body = in.readNBytes(bodyBytes);
        if (body.length != bodyBytes) {
            throw new EOFException("event body ended early");
        }
        return new Notice(
                eventName,
                traceparent,
                timestamp,
                signature,
                "true".equals(failure),
                new String(body, StandardCharsets.UTF_8));
    }

    public static void writeOutcome(OutputStream out, boolean accepted) throws IOException {
        String text = PROTOCOL_LINE + "\n" + (accepted ? ACCEPTED : REJECTED) + "\n";
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static boolean readAccepted(InputStream in) throws IOException {
        if (!PROTOCOL_LINE.equals(readLine(in))) {
            throw new IOException("unexpected outbox socket protocol");
        }
        String outcome = readLine(in);
        if (ACCEPTED.equals(outcome)) {
            return true;
        }
        if (REJECTED.equals(outcome)) {
            return false;
        }
        throw new IOException("unexpected outbox socket outcome");
    }

    private static void requireLine(String name, String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException(name + " is missing");
        }
    }

    private static String value(String line, String key) throws IOException {
        String prefix = key + ": ";
        if (line == null || !line.startsWith(prefix) || line.length() == prefix.length()) {
            throw new IOException("missing " + key);
        }
        return line.substring(prefix.length());
    }

    private static long parseTimestamp(String text) throws IOException {
        try {
            long value = Long.parseLong(text);
            if (value < 0) {
                throw new IOException("auth-timestamp is negative");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IOException("auth-timestamp is not a number", ex);
        }
    }

    private static int parseBodyLength(String text) throws IOException {
        try {
            int length = Integer.parseInt(text);
            if (length < 0 || length > MAX_BODY_BYTES) {
                throw new IOException("event body length is outside the outbox column");
            }
            return length;
        } catch (NumberFormatException ex) {
            throw new IOException("event body length is not a number", ex);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        byte[] buf = new byte[512];
        int used = 0;
        while (true) {
            int next = in.read();
            if (next == -1) {
                throw new EOFException("outbox socket closed");
            }
            if (next == '\n') {
                break;
            }
            if (next == '\r') {
                continue;
            }
            if (used == buf.length) {
                throw new IOException("outbox socket line is too long");
            }
            buf[used++] = (byte) next;
        }
        return new String(buf, 0, used, StandardCharsets.UTF_8);
    }
}
