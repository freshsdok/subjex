package com.subjex.platform.app.notification;

import com.subjex.platform.contract.identity.AccountState;
import com.subjex.platform.contract.identity.IdentityState;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EndUserNotificationDestinationResolver — resolve a channel destination from first-class end-user facts.
 * 终端通知目的地解析：按渠道从已有一等事实（已验证邮箱、手机号、微信 IdP 绑定）补全目的地。
 * <p>
 * An explicit destination is left untouched. Operator subjects are never looked up (there is no
 * operator contact directory). Missing or unverified facts do not invent an address or switch
 * channels. Resolution happens at dispatch time and does not rewrite the outbox row.
 * 已有显式目的地保持不变。操作员主体不做查找（没有操作员通讯录）。缺失或未验证的事实不得编造地址或改渠道。
 * 在调度时解析，不改写出箱行。
 */
public final class EndUserNotificationDestinationResolver {

    /**
     * Stable fail-closed reason when a subject-addressed send has no matching fact.
     * 主体寻址但没有匹配事实时的失败关闭原因。
     */
    public static final String UNRESOLVED_REASON = "notification-destination-unresolved";

    /** WeChat provider id stored on {@code end_user_idp_link} — 绑定表上的微信提供方标识. */
    static final String WECHAT_PROVIDER_ID = "wechat";

    /** WeChat issuer prefix ({@code wechat:mini} / {@code wechat:open}) — 微信发行方前缀. */
    static final String WECHAT_ISSUER_PREFIX = "wechat:";

    private final JdbcTemplate jdbc;

    public EndUserNotificationDestinationResolver(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Return the request unchanged when a destination is already present; otherwise attach a
     * channel-matched fact, or empty when that fact cannot be used.
     * 已有目的地则原样返回；否则附上与渠道匹配的事实；无法使用时返回 empty。
     */
    public Optional<NotificationRequested> resolve(NotificationRequested request) {
        Objects.requireNonNull(request, "request");
        NotificationRecipient recipient = request.recipient();
        if (recipient == null) {
            return Optional.empty();
        }
        if (recipient.destination() != null) {
            return Optional.of(request);
        }
        if (recipient.subjectId() == null) {
            return Optional.empty();
        }
        if (recipient.realm() != InboxRecipientRealm.END_USER) {
            return Optional.empty();
        }
        Optional<String> destination = lookup(request.channel(), request.tenantId(), recipient.subjectId());
        return destination.map(value -> withDestination(request, value));
    }

    private Optional<String> lookup(NotificationChannel channel, String tenantId, String subjectId) {
        Optional<String> accountId = findActiveAccount(tenantId, subjectId);
        if (accountId.isEmpty()) {
            return Optional.empty();
        }
        return switch (channel) {
            case EMAIL -> findVerifiedEmail(accountId.get());
            case SMS -> findPhone(accountId.get());
            case WECHAT_TEMPLATE -> findWeChatBinding(accountId.get());
        };
    }

    private Optional<String> findActiveAccount(String tenantId, String subjectId) {
        List<String> ids = jdbc.query(
                """
                SELECT si.account_id
                  FROM subject_identity si
                  JOIN account a ON a.account_id = si.account_id
                 WHERE si.subject_id = ?
                   AND si.tenant_id = ?
                   AND si.identity_state = ?
                   AND a.account_state = ?
                 ORDER BY si.identity_id
                 LIMIT 1
                """,
                (row, n) -> row.getString("account_id"),
                subjectId,
                tenantId,
                IdentityState.ACTIVE.name(),
                AccountState.ACTIVE.name());
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private Optional<String> findVerifiedEmail(String accountId) {
        List<String> emails = jdbc.query(
                """
                SELECT email
                  FROM end_user_profile
                 WHERE account_id = ?
                   AND email IS NOT NULL
                   AND email_verified_at IS NOT NULL
                """,
                (row, n) -> row.getString("email"),
                accountId);
        return firstPresent(emails);
    }

    private Optional<String> findPhone(String accountId) {
        List<String> phones = jdbc.query(
                """
                SELECT phone
                  FROM end_user_phone_binding
                 WHERE account_id = ?
                 ORDER BY created_at, binding_id
                 LIMIT 1
                """,
                (row, n) -> row.getString("phone"),
                accountId);
        return firstPresent(phones);
    }

    private Optional<String> findWeChatBinding(String accountId) {
        List<String> subjects = jdbc.query(
                """
                SELECT idp_subject
                  FROM end_user_idp_link
                 WHERE account_id = ?
                   AND (provider_id = ? OR issuer LIKE ?)
                 ORDER BY CASE
                       WHEN issuer = 'wechat:mini' THEN 0
                       WHEN issuer = 'wechat:open' THEN 1
                       ELSE 2
                   END, link_id
                 LIMIT 1
                """,
                (row, n) -> row.getString("idp_subject"),
                accountId,
                WECHAT_PROVIDER_ID,
                WECHAT_ISSUER_PREFIX + "%");
        return firstPresent(subjects);
    }

    private static Optional<String> firstPresent(List<String> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        String value = values.get(0);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private static NotificationRequested withDestination(NotificationRequested request, String destination) {
        NotificationRecipient current = request.recipient();
        NotificationRecipient recipient = new NotificationRecipient(
                current.realm(), current.subjectId(), destination, current.recipientTenantId());
        return new NotificationRequested(
                request.eventId(),
                request.tenantId(),
                request.templateId(),
                request.channel(),
                recipient,
                request.parameters());
    }
}
