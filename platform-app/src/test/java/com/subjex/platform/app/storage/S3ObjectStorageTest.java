package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.subjex.platform.contract.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3ObjectStorageTest — purpose: SHARED-2 S3 ObjectStorage put/get/delete via mocked S3Client.
 * Gates: tenant/key path layout; NoSuchKey → empty; reject path traversal.
 * <p>
 * 目的：SHARED-2 经 mock S3Client 测 put/get/delete。门禁：租户/键布局；NoSuchKey 空；拒绝路径穿越。
 */
class S3ObjectStorageTest {

    private S3Client client;
    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new S3ObjectStorage(client, "subjex-blobs");
    }

    @Test
    void putUsesTenantPrefixedKeyAndContentType() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        storage.put("tenant-a", "blob-1", body, "text/plain");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(req.capture(), any(RequestBody.class));
        assertEquals("subjex-blobs", req.getValue().bucket());
        assertEquals("tenant-a/blob-1", req.getValue().key());
        assertEquals("text/plain", req.getValue().contentType());
    }

    @Test
    void findReturnsContentAndMediaType() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse meta = GetObjectResponse.builder().contentType("application/pdf").build();
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                meta, AbortableInputStream.create(new ByteArrayInputStream(body)));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);

        Optional<StoredObject> found = storage.find("tenant-a", "blob-1");
        assertTrue(found.isPresent());
        assertArrayEquals(body, found.get().content());
        assertEquals("application/pdf", found.get().mediaType());
        assertEquals("tenant-a", found.get().tenantId());
        assertEquals("blob-1", found.get().objectKey());
    }

    @Test
    void findMissingKeyIsEmpty() {
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertTrue(storage.find("tenant-a", "missing").isEmpty());
    }

    @Test
    void deleteIssuesDeleteObject() {
        storage.delete("tenant-a", "blob-1");
        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(req.capture());
        assertEquals("subjex-blobs", req.getValue().bucket());
        assertEquals("tenant-a/blob-1", req.getValue().key());
    }

    @Test
    void rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> storage.put("../x", "k", new byte[0], "t"));
        assertThrows(IllegalArgumentException.class, () -> storage.put("t", "a/b", new byte[0], "t"));
    }

    @Test
    void s3KeyJoinsTenantAndObject() {
        assertEquals("acme/file-1", S3ObjectStorage.s3Key("acme", "file-1"));
    }

    @Test
    void blankBucketRejected() {
        assertThrows(IllegalArgumentException.class, () -> new S3ObjectStorage(client, "  "));
    }
}
