package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.Objects;

/**
 * InboxMessage — 站内信正文：一次发送一条规范消息（写扩散到多条 entry）。
 *
 * @param messageId primary key — 主键
 * @param tenantId owning tenant (fail-closed) — 所属租户（失败关闭）
 * @param channel SYSTEM / WORKFLOW / ORG_BROADCAST — 渠道
 * @param title short title (MVP Chinese) — 短标题（首波中文）
 * @param body body text — 正文
 * @param severity INFO / WARN / ACTION — 严重度
 * @param producerKind e.g. system / workflow / admin — 生产者种类
 * @param producerId optional producer id — 可选生产者标识
 * @param refKind optional ref kind (e.g. workflow_instance) — 可选引用种类
 * @param refId optional ref id — 可选引用 id
 * @param entityKey optional entity key — 可选实体键
 * @param declarationVersion optional declaration version — 可选声明版本
 * @param deepLink optional console-relative path — 可选控制台相对深链
 * @param createdAt created instant — 创建时间
 */
public record InboxMessage(
        String messageId,
        String tenantId,
        InboxChannel channel,
        String title,
        String body,
        InboxSeverity severity,
        String producerKind,
        String producerId,
        String refKind,
        String refId,
        String entityKey,
        Integer declarationVersion,
        String deepLink,
        Instant createdAt) {

    public InboxMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(producerKind, "producerKind");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
