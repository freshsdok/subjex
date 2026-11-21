package com.subjex.platform.app.storage;

import java.net.URI;
import java.time.Duration;

/**
 * S3GetObjectPresigner — test-fakeable S3 GET presign (WP3-2 / ADR 0015 D3).
 * 可在测试中替换的 S3 GET 预签名口；生产由 AWS SDK v2 {@code S3Presigner} 实现。
 */
@FunctionalInterface
public interface S3GetObjectPresigner {

    /**
     * Issue a presigned GET for {@code bucket}/{@code key} lasting {@code ttl}.
     * 为桶内键签发持续 {@code ttl} 的预签名 GET，不得把桶凭证交给客户端。
     */
    URI presignGet(String bucket, String key, Duration ttl);
}
