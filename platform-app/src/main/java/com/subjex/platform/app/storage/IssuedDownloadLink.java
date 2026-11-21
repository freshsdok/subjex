package com.subjex.platform.app.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * IssuedDownloadLink — a short-lived signed download URL after an ALLOW decision (WP3-2).
 * 判定允许之后签发的短时签名下载地址。
 *
 * @param url       signed GET URL (S3 presign or local HMAC redeem) — 签名 GET（S3 预签名或本地 HMAC 兑换）
 * @param expiresAt instant the link stops working — 链接失效时刻
 * @param ttl       requested/effective lifetime — 实际有效期
 * @param backend   {@code S3} or {@code LOCAL} — 后端种类
 */
public record IssuedDownloadLink(String url, Instant expiresAt, Duration ttl, String backend) {

    public static final String BACKEND_S3 = "S3";
    public static final String BACKEND_LOCAL = "LOCAL";

    public IssuedDownloadLink {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("download URL is required");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(ttl, "ttl");
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend is required");
        }
    }
}
