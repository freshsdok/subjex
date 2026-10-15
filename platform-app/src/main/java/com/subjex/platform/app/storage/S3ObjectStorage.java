package com.subjex.platform.app.storage;

import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3ObjectStorage — S3 兼容对象存储（SHARED-2）：AWS / MinIO 等实现 {@link ObjectStorage}。
 * <p>
 * Object keys are {@code {tenantId}/{objectKey}} inside one bucket. Content-Type carries media type.
 * Path-style + custom endpoint support MinIO. Does not create the bucket.
 * 对象键为桶内 {@code {tenantId}/{objectKey}}；媒体类型走 Content-Type。支持 path-style 与自定义 endpoint（MinIO）。不自动建桶。
 */
public final class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final String bucket;

    public S3ObjectStorage(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("platform.storage.s3.bucket is required");
        }
        this.bucket = bucket.trim();
    }

    /**
     * Build an S3 client from SHARED-2 settings — 按 SHARED-2 配置构建 S3 客户端。
     */
    public static S3Client createClient(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            boolean pathStyleAccess) {
        String regionName = region == null || region.isBlank() ? "us-east-1" : region.trim();
        S3ClientBuilder builder = S3Client.builder().region(Region.of(regionName));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        if (pathStyleAccess) {
            builder.serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        if (accessKey != null
                && !accessKey.isBlank()
                && secretKey != null
                && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())));
        } else {
            // Default chain (env / profile / instance role) — 默认凭证链
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    @Override
    public void put(String tenantId, String objectKey, byte[] content, String mediaType) {
        String key = s3Key(tenantId, objectKey);
        String type = mediaType == null ? "" : mediaType;
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(type.isBlank() ? "application/octet-stream" : type)
                            .build(),
                    RequestBody.fromBytes(content == null ? new byte[0] : content));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("could not store object " + objectKey, ex);
        }
    }

    @Override
    public Optional<StoredObject> find(String tenantId, String objectKey) {
        String key = s3Key(tenantId, objectKey);
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            try (response) {
                byte[] content = response.readAllBytes();
                String mediaType = response.response().contentType();
                if (mediaType == null) {
                    mediaType = "";
                }
                return Optional.of(new StoredObject(tenantId, objectKey, content, mediaType));
            }
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (IOException ex) {
            throw new IllegalStateException("could not read object " + objectKey, ex);
        } catch (RuntimeException ex) {
            // Some S3-compatible servers use 404 status without NoSuchKeyException mapping —
            // 部分兼容实现 404 未映射为 NoSuchKeyException
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (msg.contains("404") || msg.contains("not found") || msg.contains("nosuchkey")) {
                return Optional.empty();
            }
            throw new IllegalStateException("could not read object " + objectKey, ex);
        }
    }

    @Override
    public void delete(String tenantId, String objectKey) {
        String key = s3Key(tenantId, objectKey);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("could not delete object " + objectKey, ex);
        }
    }

    /** Bucket-relative key {@code tenant/object} — 桶内键 {@code 租户/对象}。 */
    static String s3Key(String tenantId, String objectKey) {
        requireSingleName(tenantId, "tenant");
        requireSingleName(objectKey, "object key");
        return tenantId + "/" + objectKey;
    }

    private static void requireSingleName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenant and object key are required");
        }
        if (value.contains("..")
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(label + " must be a single name");
        }
    }
}
