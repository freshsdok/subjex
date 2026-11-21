package com.subjex.platform.contract.notification;

/**
 * NotificationCrossTenantDeniedException — 跨租户发信被拒绝：租户 A 不得向租户 B 的收件人投递。
 * <p>
 * No outbox row is written when this is thrown.
 * 抛出时不得写出箱行。
 */
public final class NotificationCrossTenantDeniedException extends RuntimeException {

    public NotificationCrossTenantDeniedException(String sendTenantId, String recipientTenantId) {
        super("cross-tenant notification denied: send tenant "
                + sendTenantId
                + " cannot target recipient in "
                + recipientTenantId);
    }
}
