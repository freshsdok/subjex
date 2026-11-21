package com.subjex.platform.contract.consent;

import java.time.Instant;

/**
 * ConsentEvent — one append-only grant or withdraw fact.
 * 一条不可改写的授予或撤销事实。
 *
 * @param consentVersionId version on a grant; the withdrawn version on a withdraw
 * @param consentVersionId 授予时的版本；撤销时为被撤销的版本
 */
public record ConsentEvent(
        String eventId,
        String tenantId,
        String subjectId,
        String purposeKey,
        String consentVersionId,
        ConsentEventKind eventKind,
        String actorIdentityId,
        Instant occurredAt) {}
