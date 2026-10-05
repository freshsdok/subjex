package com.subjex.platform.contract.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OutboxSocketFrameTest {

    @Test
    void noticeAndOutcomeRoundTrip() throws Exception {
        OutboxSocketFrame.Notice notice = new OutboxSocketFrame.Notice(
                "TaskRecorded",
                "00-11111111111111111111111111111111-2222222222222222-01",
                "platform-operator",
                "change-me",
                true,
                "{\"eventId\":\"event-1\"}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutboxSocketFrame.writeNotice(out, notice);
        OutboxSocketFrame.writeOutcome(out, false);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        assertEquals(notice, OutboxSocketFrame.readNotice(in));
        assertFalse(OutboxSocketFrame.readAccepted(in));
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
}
