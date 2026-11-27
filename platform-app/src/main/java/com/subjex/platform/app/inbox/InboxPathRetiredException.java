package com.subjex.platform.app.inbox;

/**
 * InboxPathRetiredException — {@code /api/v1/me/inbox} is retired; use {@code /api/v1/end-user/inbox}.
 * 旧路径 {@code /api/v1/me/inbox} 已退役，请改走 {@code /api/v1/end-user/inbox}。
 */
public final class InboxPathRetiredException extends RuntimeException {

    /** Stable reason on the 410 body — 410 响应体上的稳定原因. */
    public static final String REASON = "inbox-path-retired";

    /** Product path that replaces the retired mailbox — 取代旧收件箱的产品路径. */
    public static final String USE = EndUserShellInboxEndpoint.BASE;

    public InboxPathRetiredException() {
        super(REASON);
    }
}
