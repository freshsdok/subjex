package com.subjex.platform.app.idp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IdpLinkProofStore — short-lived proof-of-possession tickets for self-link (ID-6 polish).
 * In-memory; single-node. Ticket proves the caller completed IdP callback for issuer+subject.
 * 自助绑定 PoP 短票（内存，单机）；证明调用方完成了 IdP 回调。
 */
public final class IdpLinkProofStore {

    public static final Duration TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, Proof> byToken = new ConcurrentHashMap<>();

    public IdpLinkProofStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String issue(String loginName, String issuer, String idpSubject, String providerId) {
        String login = require(loginName, "loginName");
        String iss = require(issuer, "issuer");
        String sub = require(idpSubject, "idpSubject");
        String token = "idp-proof-" + UUID.randomUUID();
        Instant exp = clock.instant().plus(TTL);
        byToken.put(token, new Proof(login, iss, sub, providerId == null ? "" : providerId.trim(), exp));
        return token;
    }

    /**
     * Consume a valid proof for the given login (one-shot). Empty if missing/expired/mismatch.
     * 一次性消费；缺失/过期/登录不匹配则空。
     */
    public Optional<Proof> consume(String proofToken, String loginName) {
        if (proofToken == null || proofToken.isBlank() || loginName == null || loginName.isBlank()) {
            return Optional.empty();
        }
        Proof proof = byToken.remove(proofToken.trim());
        if (proof == null) {
            return Optional.empty();
        }
        if (!proof.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        if (!proof.loginName().equals(loginName.trim())) {
            return Optional.empty();
        }
        return Optional.of(proof);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }

    public record Proof(String loginName, String issuer, String idpSubject, String providerId, Instant expiresAt) {}
}
