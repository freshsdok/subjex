package com.subjex.platform.app.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.subjex.platform.contract.storage.ObjectStorage;

/**
 * InboxAttachmentStagingStore — 内存暂存登记（IM-A2；对齐 CHUNK-1 会话 TTL 思路）。
 * <p>
 * Bytes already live in {@link ObjectStorage}; this map only tracks bindable staging ids.
 * Multi-pod shared staging is a later harding (same as CHUNK-1 temp dir).
 * 字节已在 ObjectStorage；本表只登记可绑定的 staging id。多副本共享后置。
 */
public final class InboxAttachmentStagingStore {

    /** Staging TTL (1h), same order as chunk sessions — 暂存 TTL（1 小时）. */
    public static final Duration TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<String, InboxAttachmentStaging> byId = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ObjectStorage objects;

    public InboxAttachmentStagingStore(Clock clock, ObjectStorage objects) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    /**
     * Register a completed upload — 登记已上传完成的对象.
     */
    public InboxAttachmentStaging put(
            String tenantId,
            String uploaderSubjectId,
            String fileName,
            String contentType,
            int byteSize,
            String storageKey,
            String checksumSha256) {
        Instant now = clock.instant();
        InboxAttachmentStaging staging = new InboxAttachmentStaging(
                UUID.randomUUID().toString(),
                tenantId.trim(),
                uploaderSubjectId.trim(),
                fileName.trim(),
                contentType.trim(),
                byteSize,
                storageKey.trim(),
                checksumSha256.trim().toLowerCase(),
                now,
                now.plus(TTL));
        byId.put(staging.stagingId(), staging);
        return staging;
    }

    public Optional<InboxAttachmentStaging> find(String stagingId) {
        purgeExpired();
        if (stagingId == null || stagingId.isBlank()) {
            return Optional.empty();
        }
        InboxAttachmentStaging staging = byId.get(stagingId.trim());
        if (staging == null || staging.expiredAt(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(staging);
    }

    /**
     * Take (remove) if owned by tenant+uploader — 校验归属后取出并移除.
     */
    public Optional<InboxAttachmentStaging> takeOwned(String tenantId, String uploaderSubjectId, String stagingId) {
        Optional<InboxAttachmentStaging> found = find(stagingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        InboxAttachmentStaging staging = found.get();
        if (!staging.tenantId().equals(tenantId.trim())
                || !staging.uploaderSubjectId().equals(uploaderSubjectId.trim())) {
            return Optional.empty();
        }
        byId.remove(staging.stagingId(), staging);
        return Optional.of(staging);
    }

    /** Drop expired metadata and best-effort delete objects — 清过期元数据并尽力删对象. */
    public int purgeExpired() {
        Instant now = clock.instant();
        List<InboxAttachmentStaging> expired = new ArrayList<>();
        for (InboxAttachmentStaging staging : byId.values()) {
            if (staging.expiredAt(now)) {
                expired.add(staging);
            }
        }
        for (InboxAttachmentStaging staging : expired) {
            if (byId.remove(staging.stagingId(), staging)) {
                try {
                    objects.delete(staging.tenantId(), staging.storageKey());
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
        }
        return expired.size();
    }
}
