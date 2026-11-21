package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.storage.ObjectStorage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ObjectStorageFactoryTest — purpose/gate WP1-4: prod selects S3 and refuses local-disk fallback
 * when S3 config is missing; non-prod local directory storage still works.
 * <p>
 * 目的/门禁 WP1-4：生产选择 S3，配置缺失时拒绝回退本地盘；非生产仍可用目录存储。
 */
class ObjectStorageFactoryTest {

    @TempDir
    Path directory;

    @Test
    void nonProdLocalUsesDirectoryStorage() {
        ObjectStorage storage = ObjectStorageFactory.create(
                "local", directory.toString(), "", "us-east-1", "", "", "", true, false);
        assertInstanceOf(LocalDirectoryObjectStorage.class, storage);
    }

    @Test
    void blankTypeDefaultsToLocalOutsideProduction() {
        ObjectStorage storage = ObjectStorageFactory.create(
                "  ", directory.toString(), "", "us-east-1", "", "", "", true, false);
        assertInstanceOf(LocalDirectoryObjectStorage.class, storage);
    }

    @Test
    void productionRejectsLocalDisk() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ObjectStorageFactory.create(
                        "local", directory.toString(), "", "us-east-1", "", "", "", true, true));
        assertTrue(ex.getMessage().contains("not allowed under prod"));
        assertTrue(ex.getMessage().contains("本地盘不可用于生产"));
    }

    @Test
    void productionBlankTypeDoesNotFallBackToLocalDisk() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ObjectStorageFactory.create(
                        "", directory.toString(), "", "us-east-1", "", "", "", true, true));
        assertTrue(ex.getMessage().contains("must be s3"));
        assertTrue(ex.getMessage().contains("拒绝默认落到本地盘"));
    }

    @Test
    void s3MissingSettingsFailFastAndDoNotUseLocalDisk() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ObjectStorageFactory.create(
                        "s3",
                        directory.toString(),
                        "",
                        "us-east-1",
                        "",
                        "",
                        "",
                        true,
                        true));
        assertTrue(ex.getMessage().contains("platform.storage.s3.endpoint"));
        assertTrue(ex.getMessage().contains("platform.storage.s3.bucket"));
        assertTrue(ex.getMessage().contains("platform.storage.s3.access-key"));
        assertTrue(ex.getMessage().contains("platform.storage.s3.secret-key"));
        assertTrue(ex.getMessage().contains("Refusing to fall back to local disk"));
    }

    @Test
    void s3MissingSettingsFailFastEvenOutsideProduction() {
        assertThrows(
                IllegalStateException.class,
                () -> ObjectStorageFactory.create(
                        "s3", directory.toString(), "http://127.0.0.1:9000", "us-east-1", "", "k", "s", true, false));
    }

    @Test
    void productionS3WithCompleteSettingsSelectsS3Backend() {
        ObjectStorage storage = ObjectStorageFactory.create(
                "s3",
                directory.toString(),
                "http://127.0.0.1:9000",
                "us-east-1",
                "subjex-blobs",
                "minio-access",
                "minio-secret",
                true,
                true);
        assertInstanceOf(S3ObjectStorage.class, storage);
    }
}
