package com.subjex.platform.app.storage;

import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * LocalDirectoryObjectStorage — 本地目录对象存储：{@link ObjectStorage} 的编译期实现。
 * <p>
 * Bytes for one tenant sit under that tenant's directory. The Spring bean is the instance the process uses.
 * ServiceLoader can also see this class; that only proves the SPI descriptor, it is not a second store.
 * 一个租户的字节放在该租户自己的目录下。进程使用的是 Spring 注册的那个实例。
 * ServiceLoader 也能看到这个类；那只是为了证明 SPI 描述文件，不是第二个存储。
 */
public final class LocalDirectoryObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalDirectoryObjectStorage() {
        this(Path.of(System.getProperty("platform.storage.directory", "object-store")));
    }

    public LocalDirectoryObjectStorage(Path root) {
        this.root = root;
    }

    @Override
    public void put(String tenantId, String objectKey, byte[] content, String mediaType) {
        put(tenantId, objectKey, mediaType, out -> out.write(content == null ? new byte[0] : content));
    }

    /**
     * Stream into a sibling temp file, then move into place so a failed write does not leave a half object.
     * 先写入同目录临时文件再改名就位，避免失败时留下半截对象。
     */
    @Override
    public void put(String tenantId, String objectKey, String mediaType, ObjectPutWriter writer) {
        if (writer == null) {
            throw new IllegalArgumentException("writer is required");
        }
        Path objectPath = resolve(tenantId, objectKey);
        Path temp = objectPath.resolveSibling(objectKey + ".part");
        try {
            Files.createDirectories(objectPath.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.writeTo(out);
            }
            Files.move(temp, objectPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(objectPath.resolveSibling(objectKey + ".type"), mediaType == null ? "" : mediaType);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best-effort cleanup of the temp file / 尽力删掉临时文件
            }
            throw new IllegalStateException("could not store object " + objectKey, ex);
        } catch (UncheckedIOException ex) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best-effort cleanup of the temp file / 尽力删掉临时文件
            }
            throw new IllegalStateException("could not store object " + objectKey, ex);
        }
    }

    @Override
    public Optional<StoredObject> find(String tenantId, String objectKey) {
        Path objectPath = resolve(tenantId, objectKey);
        if (!Files.exists(objectPath)) {
            return Optional.empty();
        }
        try {
            byte[] content = Files.readAllBytes(objectPath);
            Path typePath = objectPath.resolveSibling(objectKey + ".type");
            String mediaType = Files.exists(typePath) ? Files.readString(typePath) : "";
            return Optional.of(new StoredObject(tenantId, objectKey, content, mediaType));
        } catch (IOException ex) {
            throw new IllegalStateException("could not read object " + objectKey, ex);
        }
    }


    @Override
    public void delete(String tenantId, String objectKey) {
        Path objectPath = resolve(tenantId, objectKey);
        try {
            Files.deleteIfExists(objectPath);
            Files.deleteIfExists(objectPath.resolveSibling(objectKey + ".type"));
        } catch (IOException ex) {
            throw new IllegalStateException("could not delete object " + objectKey, ex);
        }
    }

    private Path resolve(String tenantId, String objectKey) {
        if (tenantId == null || tenantId.isBlank() || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("tenant and object key are required");
        }
        if (tenantId.contains("..") || objectKey.contains("..")
                || tenantId.indexOf('/') >= 0 || tenantId.indexOf('\\') >= 0
                || objectKey.indexOf('/') >= 0 || objectKey.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("object key must be a single name");
        }
        return root.resolve(tenantId).resolve(objectKey);
    }
}
