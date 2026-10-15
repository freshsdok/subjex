package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LocalDirChunkedBlobUploadStoreTest — CHUNK-1 init/chunk/complete/abort + failure cases.
 * 目的：分片会话成功路径与失败门禁。
 */
class LocalDirChunkedBlobUploadStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T14:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    @Test
    void initPutAssembleAbortRoundTrip() {
        LocalDirChunkedBlobUploadStore store = new LocalDirChunkedBlobUploadStore(temp, CLOCK, 4);
        ChunkedBlobUploadSession session =
                store.init("", "demo-ticket", "t-1", "attachment", "text/plain", 10, 5 * 1024 * 1024);
        assertEquals(3, session.chunkCount()); // 4+4+2
        store.putChunk(session.uploadId(), 0, "abcd".getBytes());
        store.putChunk(session.uploadId(), 1, "efgh".getBytes());
        // Idempotent retry of index 1
        store.putChunk(session.uploadId(), 1, "efgh".getBytes());
        ChunkedBlobUploadSession mid = store.putChunk(session.uploadId(), 2, "ij".getBytes());
        assertTrue(mid.isComplete());
        assertArrayEquals("abcdefghij".getBytes(), store.assemble(session.uploadId()));
        store.abort(session.uploadId());
        assertTrue(store.find(session.uploadId()).isEmpty());
        assertTrue(Files.notExists(temp.resolve(session.uploadId())));
    }

    @Test
    void rejectsOversizedTotalAndWrongChunkLength() {
        LocalDirChunkedBlobUploadStore store = new LocalDirChunkedBlobUploadStore(temp, CLOCK, 4);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.init("", "demo-ticket", "t-1", "attachment", "text/plain", 6 * 1024 * 1024, 5 * 1024 * 1024));
        ChunkedBlobUploadSession session =
                store.init("", "demo-ticket", "t-1", "attachment", "text/plain", 5, 5 * 1024 * 1024);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putChunk(session.uploadId(), 0, "toolong".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> store.assemble(session.uploadId()));
    }

    @Test
    void abortUnknownIsNoOp() {
        LocalDirChunkedBlobUploadStore store = new LocalDirChunkedBlobUploadStore(temp, CLOCK);
        store.abort("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }
}
