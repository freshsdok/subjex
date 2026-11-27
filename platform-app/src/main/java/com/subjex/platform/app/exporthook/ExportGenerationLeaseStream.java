package com.subjex.platform.app.exporthook;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * ExportGenerationLeaseStream — wraps the artifact stream and renews the generation lease while writing.
 * 包装产物输出流，在写入过程中续租。租约丢失则立即失败关闭，不发明中途续写。
 */
final class ExportGenerationLeaseStream extends FilterOutputStream {

    /** Renew at least this often while bytes are flowing — 有字节写出时至少按此时长续租. */
    static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(15);

    private final Clock clock;
    private final Duration heartbeatEvery;
    private final Predicate<Instant> renew;
    private Instant lastRenewed;
    private boolean closed;

    ExportGenerationLeaseStream(OutputStream out, Clock clock, Duration heartbeatEvery, Predicate<Instant> renew) {
        super(Objects.requireNonNull(out, "out"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatEvery =
                heartbeatEvery == null || heartbeatEvery.isZero() || heartbeatEvery.isNegative()
                        ? DEFAULT_HEARTBEAT
                        : heartbeatEvery;
        this.renew = Objects.requireNonNull(renew, "renew");
        this.lastRenewed = clock.instant();
    }

    @Override
    public void write(int b) throws IOException {
        heartbeat();
        out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        heartbeat();
        out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        heartbeat();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        heartbeat();
        out.close();
    }

    private void heartbeat() throws IOException {
        Instant now = clock.instant();
        if (lastRenewed != null && !now.isBefore(lastRenewed.plus(heartbeatEvery))) {
            if (!renew.test(now)) {
                throw new IOException("export generation lease was lost");
            }
            lastRenewed = now;
        }
    }
}
