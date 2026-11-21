package com.subjex.platform.app.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * S3PresignedDownloadIssuer — production signed download via S3 presigned GET (WP3-2).
 * 生产路径：使用已有 AWS SDK v2 S3 客户端配套的预签名 GET，不把桶凭证交给客户端。
 */
public final class S3PresignedDownloadIssuer implements ObjectDownloadUrlIssuer {

    private final S3ObjectStorage storage;
    private final Clock clock;

    public S3PresignedDownloadIssuer(S3ObjectStorage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public IssuedDownloadLink issue(String tenantId, String objectKey, Duration ttl) {
        URI url = storage.presignGet(tenantId, objectKey, ttl);
        return new IssuedDownloadLink(
                url.toString(), clock.instant().plus(ttl), ttl, IssuedDownloadLink.BACKEND_S3);
    }
}
