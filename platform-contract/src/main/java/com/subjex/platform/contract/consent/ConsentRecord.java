package com.subjex.platform.contract.consent;

import java.time.Instant;

/**
 * ConsentRecord — the current active grant for one tenant + subject + purpose, if any.
 * 某租户+主体+目的上当前仍然有效的授予（若有）。
 */
public record ConsentRecord(
        String tenantId,
        String subjectId,
        String purposeKey,
        String consentVersionId,
        String eventId,
        Instant grantedAt) {}
