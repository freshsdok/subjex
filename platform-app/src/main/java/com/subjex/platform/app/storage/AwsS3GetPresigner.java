package com.subjex.platform.app.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * AwsS3GetPresigner — wraps AWS SDK v2 {@link S3Presigner} for a presigned GET.
 * 封装 AWS SDK v2 预签名客户端，签发 S3 GET。
 */
public final class AwsS3GetPresigner implements S3GetObjectPresigner {

    private final S3Presigner presigner;

    public AwsS3GetPresigner(S3Presigner presigner) {
        this.presigner = Objects.requireNonNull(presigner, "presigner");
    }

    @Override
    public URI presignGet(String bucket, String key, Duration ttl) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("bucket and key are required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("presign TTL must be positive");
        }
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build());
        try {
            return signed.url().toURI();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("presigned GET URL is not a valid URI", ex);
        }
    }
}
