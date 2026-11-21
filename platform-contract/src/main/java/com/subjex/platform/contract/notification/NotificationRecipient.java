package com.subjex.platform.contract.notification;

import com.subjex.platform.contract.inbox.InboxRecipientRealm;

/**
 * NotificationRecipient — 通知收件人：租户上下文中的主体（操作员 / 终端用户）或显式目的地。
 * <p>
 * A subject uses the same {@link InboxRecipientRealm} tokens as the inbox. An explicit destination
 * (phone / email / channel address) is still attributed to the send tenant. If
 * {@code recipientTenantId} is set, it must equal the send tenant or the port denies the enqueue.
 * 主体沿用站内信的领域标识。显式目的地仍归属于发信租户。若填写 {@code recipientTenantId}，必须与发信租户一致，否则拒绝入队。
 */
public record NotificationRecipient(
        InboxRecipientRealm realm,
        String subjectId,
        String destination,
        String recipientTenantId) {

    public NotificationRecipient {
        subjectId = blankToNull(subjectId);
        destination = blankToNull(destination);
        recipientTenantId = blankToNull(recipientTenantId);
        if (subjectId == null && destination == null) {
            throw new IllegalArgumentException("recipient subjectId or destination required");
        }
        if (subjectId != null && realm == null) {
            throw new IllegalArgumentException("recipient realm required when subjectId is set");
        }
    }

    /** Address a subject in the send tenant — 在发信租户内按主体寻址. */
    public static NotificationRecipient ofSubject(InboxRecipientRealm realm, String subjectId) {
        return new NotificationRecipient(realm, subjectId, null, null);
    }

    /**
     * Address a subject stamped with a recipient tenant (must match the send tenant).
     * 带收件人租户戳的主体（必须与发信租户一致）。
     */
    public static NotificationRecipient ofSubject(
            InboxRecipientRealm realm, String subjectId, String recipientTenantId) {
        return new NotificationRecipient(realm, subjectId, null, recipientTenantId);
    }

    /** Explicit destination attributed to the send tenant — 显式目的地，归属发信租户. */
    public static NotificationRecipient ofDestination(String destination) {
        return new NotificationRecipient(null, null, destination, null);
    }

    /**
     * Explicit destination stamped with a recipient tenant (must match the send tenant).
     * 带收件人租户戳的显式目的地（必须与发信租户一致）。
     */
    public static NotificationRecipient ofDestination(String destination, String recipientTenantId) {
        return new NotificationRecipient(null, null, destination, recipientTenantId);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
