package com.subjex.platform.app.storage;

import com.subjex.platform.contract.storage.ObjectStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ObjectStorageFactory — selects the object-storage backend from {@code platform.storage.*}.
 * 按 {@code platform.storage.*} 选择对象存储后端。
 * <p>
 * Under {@code prod}/{@code production}, {@code local} is refused and a misconfigured {@code s3}
 * backend fails fast (no fallback to {@code object-store/}).
 * 生产配置禁止本地盘；{@code s3} 配置不完整时启动失败，不会回退到本地目录。
 */
public final class ObjectStorageFactory {

    private ObjectStorageFactory() {}

    /**
     * Build the storage adapter — 构建存储适配器。
     *
     * @param productionProfile {@code true} when {@code prod} or {@code production} is active
     */
    public static ObjectStorage create(
            String type,
            String directory,
            String s3Endpoint,
            String s3Region,
            String s3Bucket,
            String s3AccessKey,
            String s3SecretKey,
            boolean s3PathStyle,
            boolean productionProfile) {
        String mode = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) {
            if (productionProfile) {
                throw new IllegalStateException(
                        "platform.storage.type is required under prod/production and must be s3 "
                                + "(MinIO-compatible). Refusing to default to local disk. "
                                + "生产配置必须显式使用 s3（MinIO 兼容），拒绝默认落到本地盘。");
            }
            mode = "local";
        }
        if (productionProfile && "local".equals(mode)) {
            throw new IllegalStateException(
                    "platform.storage.type=local is not allowed under prod/production; "
                            + "use s3 (MinIO-compatible). Local disk is not a multi-instance plan. "
                            + "本地盘不可用于生产配置，也不得作为多实例方案；请使用 s3（MinIO 兼容）。");
        }
        return switch (mode) {
            case "local" -> new LocalDirectoryObjectStorage(
                    Path.of(directory == null || directory.isBlank() ? "object-store" : directory));
            case "s3" -> {
                requireS3Config(s3Endpoint, s3Bucket, s3AccessKey, s3SecretKey);
                yield new S3ObjectStorage(
                        S3ObjectStorage.createClient(
                                s3Endpoint, s3Region, s3AccessKey, s3SecretKey, s3PathStyle),
                        s3Bucket,
                        S3ObjectStorage.createPresigner(
                                s3Endpoint, s3Region, s3AccessKey, s3SecretKey, s3PathStyle));
            }
            default -> throw new IllegalArgumentException(
                    "platform.storage.type must be local or s3, got: " + type);
        };
    }

    /**
     * Fail closed when S3 settings are incomplete — never fall back to local disk.
     * S3 配置不完整则失败关闭，绝不回退到本地盘。
     */
    static void requireS3Config(String endpoint, String bucket, String accessKey, String secretKey) {
        List<String> missing = new ArrayList<>();
        if (isBlank(endpoint)) {
            missing.add("platform.storage.s3.endpoint");
        }
        if (isBlank(bucket)) {
            missing.add("platform.storage.s3.bucket");
        }
        if (isBlank(accessKey)) {
            missing.add("platform.storage.s3.access-key");
        }
        if (isBlank(secretKey)) {
            missing.add("platform.storage.s3.secret-key");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "S3 object storage is misconfigured; missing "
                            + String.join(", ", missing)
                            + ". Refusing to fall back to local disk (object-store/). "
                            + "S3 对象存储配置不完整（缺少 "
                            + String.join(", ", missing)
                            + "），拒绝回退到本地目录。");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
