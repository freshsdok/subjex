package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * InboxSendRequest — 发送请求：一条消息 + 收件人列表（同事务写扩散）。
 *
 * @param tenantId target tenant (one row per tenant for cross-tenant SYSTEM) — 目标租户
 * @param channel message channel — 渠道
 * @param title title — 标题
 * @param body body — 正文
 * @param severity severity — 严重度
 * @param producerKind producer kind — 生产者种类
 * @param producerId optional producer id — 可选生产者 id
 * @param refKind optional ref kind — 可选引用种类
 * @param refId optional ref id — 可选引用 id
 * @param entityKey optional entity key — 可选实体键
 * @param declarationVersion optional declaration version — 可选声明版本
 * @param deepLink optional deep link — 可选深链
 * @param recipients non-empty recipients — 非空收件人
 * @param createdAt created instant — 创建时间
 */
public record InboxSendRequest(
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
        List<InboxRecipient> recipients,
        Instant createdAt) {

    public InboxSendRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(producerKind, "producerKind");
        Objects.requireNonNull(recipients, "recipients");
        Objects.requireNonNull(createdAt, "createdAt");
        tenantId = tenantId.trim();
        title = title.trim();
        body = body.trim();
        producerKind = producerKind.trim();
        if (tenantId.isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        if (producerKind.isEmpty()) {
            throw new IllegalArgumentException("producerKind must not be blank");
        }
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients must not be empty");
        }
        recipients = List.copyOf(recipients);
    }
}
