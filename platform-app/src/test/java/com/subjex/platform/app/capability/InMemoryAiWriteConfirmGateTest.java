package com.subjex.platform.app.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * InMemoryAiWriteConfirmGateTest — 确认票一次性、过期与错配失败关闭。
 */
class InMemoryAiWriteConfirmGateTest {

    @Test
    void issueThenConsumeOnce() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T06:00:00Z"), ZoneOffset.UTC);
        InMemoryAiWriteConfirmGate gate = new InMemoryAiWriteConfirmGate(clock);
        LocalStubModelCompletionClient client = new LocalStubModelCompletionClient();
        ModelCompletionRequest req = new ModelCompletionRequest("ai.summarizePreview", "body text");
        ModelCompletionResult preview = client.complete(req);
        AiWriteConfirmTicket ticket = gate.issue(req, preview);
        assertDoesNotThrow(() -> gate.consume(ticket));
        assertThrows(CapabilityRejected.class, () -> gate.consume(ticket));
    }

    @Test
    void expiredTicketIsRejected() {
        Instant t0 = Instant.parse("2026-10-09T06:00:00Z");
        MutableClock clock = new MutableClock(t0);
        InMemoryAiWriteConfirmGate gate = new InMemoryAiWriteConfirmGate(clock, Duration.ofMinutes(1));
        LocalStubModelCompletionClient client = new LocalStubModelCompletionClient();
        ModelCompletionRequest req = new ModelCompletionRequest("ai.summarizePreview", "body");
        AiWriteConfirmTicket ticket = gate.issue(req, client.complete(req));
        clock.set(t0.plus(Duration.ofMinutes(2)));
        assertThrows(CapabilityRejected.class, () -> gate.consume(ticket));
    }

    @Test
    void mismatchedTicketIsRejected() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T06:00:00Z"), ZoneOffset.UTC);
        InMemoryAiWriteConfirmGate gate = new InMemoryAiWriteConfirmGate(clock);
        LocalStubModelCompletionClient client = new LocalStubModelCompletionClient();
        ModelCompletionRequest req = new ModelCompletionRequest("ai.summarizePreview", "body");
        ModelCompletionResult preview = client.complete(req);
        AiWriteConfirmTicket issued = gate.issue(req, preview);
        AiWriteConfirmTicket forged = new AiWriteConfirmTicket(
                issued.ticketId(),
                issued.capabilityId(),
                "wrong-digest",
                issued.previewDigest(),
                issued.expiresAt());
        assertThrows(CapabilityRejected.class, () -> gate.consume(forged));
    }

    /** Test clock — 测试用可变时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
