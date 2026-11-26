package com.subjex.platform.app.support;

import java.time.Instant;
import java.util.List;

/**
 * TenantKeyset — tiny tenant-scoped keyset (cursor) page helper: clamp limits and encode a time+id cursor.
 * 租户内键集（游标）分页小助手：限制条数，并用时间+标识编码游标。
 * <p>
 * Not a search product. Callers that already page by offset keep compiling without this type.
 * 不是搜索产品。仍按偏移翻页的调用方不必使用本类型，现有代码可继续编译。
 */
public final class TenantKeyset {

    /** Default page size for lists this helper caps — 本助手为列表设定的默认条数. */
    public static final int DEFAULT_LIMIT = 50;

    /** Hard cap for a single page — 单页上限. */
    public static final int MAX_LIMIT = 100;

    /** Default page size while streaming an export bundle — 生成导出包时的默认翻页条数. */
    public static final int EXPORT_PAGE_LIMIT = 50;

    private TenantKeyset() {}

    public static int clamp(Integer limit) {
        return clamp(limit, DEFAULT_LIMIT, MAX_LIMIT);
    }

    public static int clamp(Integer limit, int defaultLimit, int maxLimit) {
        int fallback = defaultLimit < 1 ? DEFAULT_LIMIT : defaultLimit;
        int cap = maxLimit < 1 ? MAX_LIMIT : maxLimit;
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return Math.min(limit, cap);
    }

    /**
     * Encode {@code instant} plus a non-blank id as a cursor; blank id or null instant yields {@code null}.
     * 把时间与非空标识编成游标；时间为空或标识空白则返回 {@code null}。
     */
    public static String encode(Instant instant, String id) {
        if (instant == null || id == null || id.isBlank()) {
            return null;
        }
        return instant.toString() + "|" + id.trim();
    }

    /**
     * Decode a cursor from {@link #encode}; invalid input yields {@code null} (caller treats as start).
     * 解码 {@link #encode} 的游标；非法输入返回 {@code null}（调用方视为从开头翻页）。
     */
    public static Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int bar = raw.indexOf('|');
        if (bar <= 0 || bar == raw.length() - 1) {
            return null;
        }
        try {
            Instant instant = Instant.parse(raw.substring(0, bar).trim());
            String id = raw.substring(bar + 1).trim();
            if (id.isEmpty()) {
                return null;
            }
            return new Cursor(instant, id);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Next cursor when a page was full; {@code null} when this was the last page.
     * 本页已满时的下一游标；不足一页则为最后一页，返回 {@code null}。
     */
    public static <T> String nextCursor(List<T> page, int limit, CursorFactory<T> factory) {
        if (page == null || page.size() < limit || page.isEmpty() || factory == null) {
            return null;
        }
        T last = page.get(page.size() - 1);
        return factory.encode(last);
    }

    @FunctionalInterface
    public interface CursorFactory<T> {
        String encode(T item);
    }

    /** A decoded keyset cursor — 已解码的键集游标. */
    public record Cursor(Instant instant, String id) {}
}
