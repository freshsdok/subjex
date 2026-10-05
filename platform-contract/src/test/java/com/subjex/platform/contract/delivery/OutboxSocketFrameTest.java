package com.subjex.platform.contract.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxSocketFrameTest {

    private static final String SECRET = "a".repeat(32);

    @Test
    void noticeAndOutcomeRoundTrip() throws Exception {
        long ts = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli();
        String body = "{\"eventId\":\"event-1\"}";
        String signature = OutboxHmac.sign(
                SECRET, "TaskRecorded", "00-11111111111111111111111111111111-2222222222222222-01", true, ts, body);
        OutboxSocketFrame.Notice notice = new OutboxSocketFrame.Notice(
                "TaskRecorded",
                "00-11111111111111111111111111111111-2222222222222222-01",
                ts,
                signature,
                true,
                body);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutboxSocketFrame.writeNotice(out, notice);
        OutboxSocketFrame.writeOutcome(out, false);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        assertEquals(notice, OutboxSocketFrame.readNotice(in));
        assertFalse(OutboxSocketFrame.readAccepted(in));
        assertTrue(OutboxHmac.verify(
                SECRET,
                notice.eventName(),
                notice.traceparent(),
                notice.failureRequested(),
                notice.authTimestampMillis(),
                notice.eventBody(),
                notice.authSignature(),
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC)));
    }

    @Test
    void acceptedOutcomeRoundTrips() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutboxSocketFrame.writeOutcome(out, true);
        assertTrue(OutboxSocketFrame.readAccepted(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void unknownProtocolIsRejected() {
        byte[] raw = "not-the-outbox\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> OutboxSocketFrame.readNotice(new ByteArrayInputStream(raw)));
    }

    @Test
    void shortSecretIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OutboxHmac.requireSecret("too-short"));
    }
}
