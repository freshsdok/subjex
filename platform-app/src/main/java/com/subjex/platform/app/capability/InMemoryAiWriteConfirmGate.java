package com.subjex.platform.app.capability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryAiWriteConfirmGate — 进程内确认门闩（开发/测试）；生产可换持久化实现。
 * <p>
 * Default TTL 15 minutes. Does not perform writes itself — only authorizes them.
 * 默认 15 分钟 TTL。本身不写库，只授权写回。
 */
public final class InMemoryAiWriteConfirmGate implements AiWriteConfirmGate {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, AiWriteConfirmTicket> open = new ConcurrentHashMap<>();

    public InMemoryAiWriteConfirmGate(Clock clock) {
        this(clock, DEFAULT_TTL);
    }

    public InMemoryAiWriteConfirmGate(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    @Override
    public AiWriteConfirmTicket issue(ModelCompletionRequest request, ModelCompletionResult preview) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(preview, "preview");
        CapabilityId.parse(request.capabilityId()); // fail-closed unknown id
        String previewDigest = LocalStubModelCompletionClient.sha256Hex(preview.text());
        Instant expires = clock.instant().plus(ttl);
        AiWriteConfirmTicket ticket = new AiWriteConfirmTicket(
                UUID.randomUUID().toString(),
                request.capabilityId(),
                preview.inputDigest(),
                previewDigest,
                expires);
        open.put(ticket.ticketId(), ticket);
        return ticket;
    }

    @Override
    public void consume(AiWriteConfirmTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        AiWriteConfirmTicket held = open.remove(ticket.ticketId());
        if (held == null) {
            throw new CapabilityRejected("AI write confirm ticket missing or already used: " + ticket.ticketId());
        }
        if (clock.instant().isAfter(held.expiresAt())) {
            throw new CapabilityRejected("AI write confirm ticket expired: " + ticket.ticketId());
        }
        if (!held.capabilityId().equals(ticket.capabilityId())
                || !held.inputDigest().equals(ticket.inputDigest())
                || !held.previewDigest().equals(ticket.previewDigest())) {
            throw new CapabilityRejected("AI write confirm ticket mismatch: " + ticket.ticketId());
        }
    }
}
