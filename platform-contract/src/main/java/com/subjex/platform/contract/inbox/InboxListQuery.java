package com.subjex.platform.contract.inbox;

/**
 * InboxListQuery — 列表过滤：未读 / 渠道 / 条数上限 / 偏移。
 *
 * @param unreadOnly when true, only unread entries — 为 true 时仅未读
 * @param channel optional channel filter; null = all — 可选渠道过滤；null 为全部
 * @param limit max rows (clamped by port, default 50) — 最大行数（端口钳制，默认 50）
 * @param offset skip rows (clamped ≥ 0) — 跳过行数（≥ 0）
 */
public record InboxListQuery(boolean unreadOnly, InboxChannel channel, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public InboxListQuery {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        if (offset < 0) {
            offset = 0;
        }
    }

    /** Back-compat three-arg (offset 0) — 兼容三参（偏移 0）. */
    public InboxListQuery(boolean unreadOnly, InboxChannel channel, int limit) {
        this(unreadOnly, channel, limit, 0);
    }

    /** All channels, unread + read, default limit — 全渠道、含已读，默认条数. */
    public static InboxListQuery defaults() {
        return new InboxListQuery(false, null, DEFAULT_LIMIT, 0);
    }
}
