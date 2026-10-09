package com.subjex.platform.contract.config;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * ConfigETags — 配置 ETag：由修订号生成 / 解析 {@code If-Match} / {@code If-None-Match}（Config-5c）。
 * <p>
 * Wire form is a strong tag {@code "N"} where N is the revision (0 = local / no override row).
 * 线上形态为强标签 {@code "N"}，N 为修订号（0 = 本地/无覆盖行）。
 */
public final class ConfigETags {

    public static final String HEADER_ETAG = "ETag";
    public static final String HEADER_IF_MATCH = "If-Match";
    public static final String HEADER_IF_NONE_MATCH = "If-None-Match";

    private ConfigETags() {}

    /** Quoted ETag for a revision — 修订号的带引号 ETag。 */
    public static String ofRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        return "\"" + revision + "\"";
    }

    /**
     * Whether {@code If-None-Match} matches the current revision (→ 304).
     * {@code *} matches any existing representation (revision ≥ 0 always true here).
     */
    public static boolean noneMatchHits(String ifNoneMatch, long currentRevision) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String token : splitTags(ifNoneMatch)) {
            if ("*".equals(token)) {
                return true;
            }
            OptionalLong parsed = parseOne(token);
            if (parsed.isPresent() && parsed.getAsLong() == currentRevision) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code If-Match} allows the write. Missing/blank header → allow (compat).
     * {@code *} allows when a representation exists (always for our GET-able keys).
     * 无头则放行（兼容）；{@code *} 表示任意现存表示。
     */
    public static boolean matchAllows(String ifMatch, long currentRevision) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return true;
        }
        for (String token : splitTags(ifMatch)) {
            if ("*".equals(token)) {
                return true;
            }
            OptionalLong parsed = parseOne(token);
            if (parsed.isPresent() && parsed.getAsLong() == currentRevision) {
                return true;
            }
        }
        return false;
    }

    /** Parse a single tag or the first numeric tag from a header value. */
    public static OptionalLong parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        String token = raw.trim();
        if (token.regionMatches(true, 0, "W/", 0, 2)) {
            token = token.substring(2).trim();
        }
        if (token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"') {
            token = token.substring(1, token.length() - 1);
        }
        if ("*".equals(token)) {
            return OptionalLong.empty();
        }
        try {
            long revision = Long.parseLong(token);
            if (revision < 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(revision);
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
    }

    private static List<String> splitTags(String header) {
        List<String> tags = new ArrayList<>();
        int start = 0;
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                String piece = header.substring(start, i).trim();
                if (!piece.isEmpty()) {
                    tags.add(piece);
                }
                start = i + 1;
            }
        }
        String tail = header.substring(start).trim();
        if (!tail.isEmpty()) {
            tags.add(tail);
        }
        return tags;
    }
}
