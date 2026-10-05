package com.subjex.platform.contract.delivery;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * OutboxSocketFrame — 出箱套接字帧：一条出箱事件在两个进程之间的线路格式。
 * <p>
 * platform-app writes one frame. sample-consumer reads it and answers accepted or rejected.
 * The event body is the text stored in the outbox, not a second copy built beside the row.
 * platform-app 写入一帧。sample-consumer 读入后回答 accepted 或 rejected。
 * 事件正文是出箱里存的文本，不是行旁边另做的一份。
 */
public final class OutboxSocketFrame {

    public static final String PROTOCOL_LINE = "SUBJEX-OUTBOX 1";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";
    public static final int MAX_BODY_BYTES = 4000;

    private OutboxSocketFrame() {
    }

    /**
     * Notice — 套接字上的一条出箱通知：事件名、追踪、操作员、正文。
     * <p>
     * {@code eventBody} is the outbox column. {@code traceparent} continues the publisher span.
     * {@code failureRequested} asks the sample path to reject after it has taken the notice, so the breaker can open.
     * {@code eventBody} 是出箱列。{@code traceparent} 续上发布方的跨度。
     * {@code failureRequested} 要求示例路径在收下通知后拒绝，以便熔断器打开。
     */
    public record Notice(
            String eventName,
            String traceparent,
            String operatorName,
            String operatorPassword,
            boolean failureRequested,
            String eventBody) {
    }

    public static void writeNotice(OutputStream out, Notice notice) throws IOException {
        requireLine("event-name", notice.eventName());
        requireLine("traceparent", notice.traceparent());
        requireLine("operator", notice.operatorName());
        requireLine("operator-password", notice.operatorPassword());
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
        header.append("operator: ").append(notice.operatorName()).append('\n');
        header.append("operator-password: ").append(notice.operatorPassword()).append('\n');
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
        String operatorName = value(readLine(in), "operator");
        String operatorPassword = value(readLine(in), "operator-password");
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
                operatorName,
                operatorPassword,
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
