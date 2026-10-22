package com.subjex.platform.contract.questionnaire;

import java.time.Instant;

/**
 * QuestionnaireCollectToken — 匿名采集令牌（能力 URL 密钥，非操作员会话）。
 *
 * @param tokenId opaque secret / 不透明密钥
 * @param questionnaireId questionnaire / 问卷 id
 * @param tenantId tenant / 租户
 * @param createdAt issued at / 签发时间
 * @param revokedAt null when active / 活跃时为 null
 */
public record QuestionnaireCollectToken(
        String tokenId,
        String questionnaireId,
        String tenantId,
        Instant createdAt,
        Instant revokedAt) {

    /** Whether the token may still be used — 令牌是否仍可用. */
    public boolean active() {
        return revokedAt == null;
    }
}
