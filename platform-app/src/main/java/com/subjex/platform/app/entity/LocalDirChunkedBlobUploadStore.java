package com.subjex.platform.app.entity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * LocalDirChunkedBlobUploadStore — 本地临时目录分片会话（CHUNK-1 MVP）。
 * <p>
 * Layout: {@code root/uploadId/meta.txt} + {@code root/uploadId/c/{index}}.
 * In-memory index mirrors disk for quick lookups; purge removes both.
 * 布局见上；内存索引加速查找；purge 同时清磁盘。
 */
public final class LocalDirChunkedBlobUploadStore implements ChunkedBlobUploadStore {

    private static final Pattern FIELD_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,127}");
    private static final Pattern UPLOAD_ID =
            Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");

    private final Path root;
    private final Clock clock;
    private final int chunkSize;
    private final ConcurrentHashMap<String, ChunkedBlobUploadSession> sessions = new ConcurrentHashMap<>();

    public LocalDirChunkedBlobUploadStore(Path root, Clock clock) {
        this(root, clock, DEFAULT_CHUNK_SIZE);
    }

    public LocalDirChunkedBlobUploadStore(Path root, Clock clock, int chunkSize) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (chunkSize < 1 || chunkSize > JdbcEntityBlobStore.MAX_BYTES) {
            throw new IllegalArgumentException("chunkSize out of range");
        }
        this.chunkSize = chunkSize;
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot create chunk upload root " + root, ex);
        }
    }

    @Override
    public ChunkedBlobUploadSession init(
            String blobTenant,
            String entityKey,
            String recordId,
            String fieldName,
            String contentType,
            long totalBytes,
            int maxBytes) {
        purgeExpired();
        String tid = blobTenant == null ? "" : blobTenant;
        requireToken(entityKey, "entityKey");
        requireToken(recordId, "recordId");
        if (fieldName == null || !FIELD_NAME.matcher(fieldName).matches()) {
            throw new IllegalArgumentException("fieldName must be an identifier");
        }
        if (totalBytes < 0 || totalBytes > maxBytes) {
            throw new IllegalArgumentException("totalBytes must be 0.." + maxBytes);
        }
        String type =
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
        if (type.length() > 128) {
            throw new IllegalArgumentException("contentType too long");
        }
        int count = totalBytes == 0 ? 0 : (int) ((totalBytes + chunkSize - 1) / chunkSize);
        String uploadId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        ChunkedBlobUploadSession session = new ChunkedBlobUploadSession(
                uploadId,
                tid,
                entityKey.trim(),
                recordId.trim(),
                fieldName,
                type,
                totalBytes,
                chunkSize,
                count,
                Set.of(),
                now);
        Path dir = sessionDir(uploadId);
        try {
            Files.createDirectories(dir.resolve("c"));
            writeMeta(dir, session);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot create upload session", ex);
        }
        sessions.put(uploadId, session);
        return session;
    }

    @Override
    public Optional<ChunkedBlobUploadSession> find(String uploadId) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            return Optional.empty();
        }
        ChunkedBlobUploadSession cached = sessions.get(uploadId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path dir = sessionDir(uploadId);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try {
            ChunkedBlobUploadSession loaded = readMeta(dir);
            sessions.put(uploadId, loaded);
            return Optional.of(loaded);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    @Override
    public ChunkedBlobUploadSession putChunk(String uploadId, int chunkIndex, byte[] data) {
        ChunkedBlobUploadSession session =
                find(uploadId).orElseThrow(() -> new IllegalArgumentException("unknown uploadId"));
        if (chunkIndex < 0 || (session.chunkCount() > 0 && chunkIndex >= session.chunkCount())) {
            throw new IllegalArgumentException("chunkIndex out of range");
        }
        if (session.chunkCount() == 0) {
            throw new IllegalArgumentException("upload has no chunks (totalBytes=0); complete directly");
        }
        byte[] bytes = data == null ? new byte[0] : data;
        int expected = expectedChunkLength(session, chunkIndex);
        if (bytes.length != expected) {
            throw new IllegalArgumentException(
                    "chunk length mismatch: expected " + expected + ", got " + bytes.length);
        }
        Path chunkFile = sessionDir(uploadId).resolve("c").resolve(Integer.toString(chunkIndex));
        try {
            Files.write(chunkFile, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write chunk " + chunkIndex, ex);
        }
        Set<Integer> next = new LinkedHashSet<>(session.receivedIndices());
        next.add(chunkIndex);
        ChunkedBlobUploadSession updated = new ChunkedBlobUploadSession(
                session.uploadId(),
                session.blobTenant(),
                session.entityKey(),
                session.recordId(),
                session.fieldName(),
                session.contentType(),
                session.totalBytes(),
                session.chunkSize(),
                session.chunkCount(),
                next,
                session.createdAt());
        try {
            writeMeta(sessionDir(uploadId), updated);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot update session meta", ex);
        }
        sessions.put(uploadId, updated);
        return updated;
    }

    @Override
    public byte[] assemble(String uploadId) {
        ChunkedBlobUploadSession session =
                find(uploadId).orElseThrow(() -> new IllegalArgumentException("unknown uploadId"));
        if (!session.isComplete()) {
            throw new IllegalArgumentException("upload incomplete: missing chunks");
        }
        if (session.totalBytes() == 0) {
            return new byte[0];
        }
        byte[] out = new byte[(int) session.totalBytes()];
        int offset = 0;
        Path chunks = sessionDir(uploadId).resolve("c");
        try {
            for (int i = 0; i < session.chunkCount(); i++) {
                byte[] part = Files.readAllBytes(chunks.resolve(Integer.toString(i)));
                System.arraycopy(part, 0, out, offset, part.length);
                offset += part.length;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot assemble upload " + uploadId, ex);
        }
        if (offset != session.totalBytes()) {
            throw new IllegalStateException("assembled size mismatch");
        }
        return out;
    }

    @Override
    public void abort(String uploadId) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            return;
        }
        sessions.remove(uploadId);
        Path dir = sessionDir(uploadId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup — 尽力清理
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public int purgeExpired() {
        Instant cutoff = clock.instant().minusSeconds(SESSION_TTL_SECONDS);
        int removed = 0;
        for (ChunkedBlobUploadSession session : sessions.values()) {
            if (session.createdAt().isBefore(cutoff)) {
                abort(session.uploadId());
                removed++;
            }
        }
        // Also scan disk for orphans — 扫描磁盘孤儿会话
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                if (!UPLOAD_ID.matcher(id).matches()) {
                    continue;
                }
                Optional<ChunkedBlobUploadSession> found = find(id);
                if (found.isEmpty() || found.get().createdAt().isBefore(cutoff)) {
                    abort(id);
                    removed++;
                }
            }
        } catch (IOException ignored) {
            // ignore listing failures
        }
        return removed;
    }

    private static int expectedChunkLength(ChunkedBlobUploadSession session, int chunkIndex) {
        if (chunkIndex < session.chunkCount() - 1) {
            return session.chunkSize();
        }
        long rem = session.totalBytes() - (long) chunkIndex * session.chunkSize();
        return (int) rem;
    }

    private Path sessionDir(String uploadId) {
        return root.resolve(uploadId);
    }

    private static void writeMeta(Path dir, ChunkedBlobUploadSession session) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("uploadId=").append(session.uploadId()).append('\n');
        sb.append("blobTenant=").append(session.blobTenant()).append('\n');
        sb.append("entityKey=").append(session.entityKey()).append('\n');
        sb.append("recordId=").append(session.recordId()).append('\n');
        sb.append("fieldName=").append(session.fieldName()).append('\n');
        sb.append("contentType=").append(session.contentType()).append('\n');
        sb.append("totalBytes=").append(session.totalBytes()).append('\n');
        sb.append("chunkSize=").append(session.chunkSize()).append('\n');
        sb.append("chunkCount=").append(session.chunkCount()).append('\n');
        sb.append("createdAt=").append(session.createdAt()).append('\n');
        sb.append("received=");
        boolean first = true;
        for (Integer idx : session.receivedIndices()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(idx);
            first = false;
        }
        sb.append('\n');
        Files.writeString(dir.resolve("meta.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static ChunkedBlobUploadSession readMeta(Path dir) throws IOException {
        String text = Files.readString(dir.resolve("meta.txt"), StandardCharsets.UTF_8);
        String uploadId = null;
        String blobTenant = "";
        String entityKey = null;
        String recordId = null;
        String fieldName = null;
        String contentType = "application/octet-stream";
        long totalBytes = 0;
        int chunkSize = DEFAULT_CHUNK_SIZE;
        int chunkCount = 0;
        Instant createdAt = Instant.EPOCH;
        Set<Integer> received = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            switch (k) {
                case "uploadId" -> uploadId = v;
                case "blobTenant" -> blobTenant = v;
                case "entityKey" -> entityKey = v;
                case "recordId" -> recordId = v;
                case "fieldName" -> fieldName = v;
                case "contentType" -> contentType = v;
                case "totalBytes" -> totalBytes = Long.parseLong(v);
                case "chunkSize" -> chunkSize = Integer.parseInt(v);
                case "chunkCount" -> chunkCount = Integer.parseInt(v);
                case "createdAt" -> createdAt = Instant.parse(v);
                case "received" -> {
                    if (!v.isBlank()) {
                        for (String part : v.split(",")) {
                            received.add(Integer.parseInt(part.trim()));
                        }
                    }
                }
                default -> {
                    // ignore unknown
                }
            }
        }
        // Reconcile received from disk files — 与磁盘分片文件对齐
        Path chunks = dir.resolve("c");
        if (Files.isDirectory(chunks)) {
            try (Stream<Path> files = Files.list(chunks)) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (name.matches("\\d+")) {
                        received.add(Integer.parseInt(name));
                    }
                }
            }
        }
        return new ChunkedBlobUploadSession(
                uploadId,
                blobTenant,
                entityKey,
                recordId,
                fieldName,
                contentType,
                totalBytes,
                chunkSize,
                chunkCount,
                received,
                createdAt);
    }

    private static void requireToken(String value, String label) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("..")) {
            throw new IllegalArgumentException(label + " must be a single token");
        }
    }
}
