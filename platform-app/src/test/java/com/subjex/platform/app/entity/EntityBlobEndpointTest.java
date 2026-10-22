package com.subjex.platform.app.entity;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


/**
 * EntityBlobEndpointTest — purpose: ES-3 blob upload/download REST with entity AuthZ;
 * SHARED-3 multipart; CHUNK-1 chunked upload sessions.
 * Gates: missing record 404; download scoped to entity+record; permission via DeclarationAccess.
 * <p>
 * 目的：ES-3 附件 REST + 鉴权；SHARED-3 multipart；CHUNK-1 分片上传。
 * 门禁：无记录 404；下载绑定实体与记录。
 */
@WebMvcTest(controllers = EntityBlobEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    EntityBlobEndpointTest.GuardConfiguration.class
})
class EntityBlobEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EffectiveDeclarationService effective;

    @MockitoBean
    private EntityBlobStore blobs;

    @MockitoBean
    private GenericEntityStore tableStore;

    @MockitoBean
    private HybridEntityStore hybridStore;

    @MockitoBean
    private ChunkedBlobUploadStore chunkedUploads;

    @Test
    void uploadDownloadAndList() throws Exception {
        RenderedEntity entity = tableTicket();
        when(effective.runtimeEntity(isNull(), eq("demo-ticket"))).thenReturn(Optional.of(entity));
        when(tableStore.findById(any(), eq("t-1"), isNull())).thenReturn(Optional.of(Map.of("ticketId", "t-1")));
        EntityBlobMetadata meta = new EntityBlobMetadata(
                "blob-1",
                "",
                "demo-ticket",
                "t-1",
                "attachment",
                "application/octet-stream",
                5,
                "blob-1",
                "abc",
                Instant.parse("2026-10-10T16:00:00Z"));
        when(blobs.put(eq(""), eq("demo-ticket"), eq("t-1"), eq("attachment"), any(), any()))
                .thenReturn(meta);
        when(blobs.listForRecord("", "demo-ticket", "t-1")).thenReturn(List.of(meta));
        when(blobs.findMetadata("", "blob-1")).thenReturn(Optional.of(meta));
        when(blobs.findContent("", "blob-1")).thenReturn(Optional.of("hello".getBytes()));

        mockMvc.perform(post(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs")
                        .param("fieldName", "attachment")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("hello")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobId").value("blob-1"));

        mockMvc.perform(get(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobs[0].blobId").value("blob-1"));

        mockMvc.perform(get(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs/blob-1")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().bytes("hello".getBytes()));
    }


    /**
     * SHARED-3: multipart/form-data upload with part {@code file}.
     * SHARED-3：multipart/form-data 上传，部件名为 {@code file}。
     */
    @Test
    void uploadMultipart() throws Exception {
        RenderedEntity entity = tableTicket();
        when(effective.runtimeEntity(isNull(), eq("demo-ticket"))).thenReturn(Optional.of(entity));
        when(tableStore.findById(any(), eq("t-1"), isNull())).thenReturn(Optional.of(Map.of("ticketId", "t-1")));
        EntityBlobMetadata meta = new EntityBlobMetadata(
                "blob-mp",
                "",
                "demo-ticket",
                "t-1",
                "attachment",
                "text/plain",
                5,
                "blob-mp",
                "def",
                Instant.parse("2026-10-15T12:00:00Z"));
        when(blobs.put(eq(""), eq("demo-ticket"), eq("t-1"), eq("attachment"), any(), eq("text/plain")))
                .thenReturn(meta);

        MockMultipartFile file =
                new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs")
                        .file(file)
                        .param("fieldName", "attachment")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobId").value("blob-mp"))
                .andExpect(jsonPath("$.contentType").value("text/plain"));
    }

    @Test
    void uploadMultipartRejectsEmptyFile() throws Exception {
        when(effective.runtimeEntity(isNull(), eq("demo-ticket"))).thenReturn(Optional.of(tableTicket()));
        when(tableStore.findById(any(), eq("t-1"), isNull())).thenReturn(Optional.of(Map.of("ticketId", "t-1")));
        MockMultipartFile empty =
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs")
                        .file(empty)
                        .param("fieldName", "attachment")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadFailsWhenRecordMissing() throws Exception {
        when(effective.runtimeEntity(isNull(), eq("demo-ticket"))).thenReturn(Optional.of(tableTicket()));
        when(tableStore.findById(any(), eq("missing"), isNull())).thenReturn(Optional.empty());
        mockMvc.perform(post(JsonApi.BASE + "/entities/demo-ticket/records/missing/blobs")
                        .param("fieldName", "attachment")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("x")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }


    @Test
    void chunkedUploadInitPutComplete() throws Exception {
        when(effective.runtimeEntity(isNull(), eq("demo-ticket"))).thenReturn(Optional.of(tableTicket()));
        when(tableStore.findById(any(), eq("t-1"), isNull())).thenReturn(Optional.of(Map.of("ticketId", "t-1")));
        ChunkedBlobUploadSession session = new ChunkedBlobUploadSession(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "",
                "demo-ticket",
                "t-1",
                "attachment",
                "application/octet-stream",
                5,
                256 * 1024,
                1,
                Set.of(),
                Instant.parse("2026-10-15T14:00:00Z"));
        when(chunkedUploads.init(eq(""), eq("demo-ticket"), eq("t-1"), eq("attachment"), any(), eq(5L), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(session);
        ChunkedBlobUploadSession withChunk = new ChunkedBlobUploadSession(
                session.uploadId(),
                session.blobTenant(),
                session.entityKey(),
                session.recordId(),
                session.fieldName(),
                session.contentType(),
                session.totalBytes(),
                session.chunkSize(),
                session.chunkCount(),
                Set.of(0),
                session.createdAt());
        when(chunkedUploads.find(session.uploadId())).thenReturn(Optional.of(session), Optional.of(withChunk));
        when(chunkedUploads.putChunk(eq(session.uploadId()), eq(0), any())).thenReturn(withChunk);
        when(chunkedUploads.assemble(session.uploadId())).thenReturn("hello".getBytes());
        EntityBlobMetadata meta = new EntityBlobMetadata(
                "blob-c1",
                "",
                "demo-ticket",
                "t-1",
                "attachment",
                "application/octet-stream",
                5,
                "blob-c1",
                "abc",
                Instant.parse("2026-10-15T14:00:00Z"));
        when(blobs.put(eq(""), eq("demo-ticket"), eq("t-1"), eq("attachment"), any(), any())).thenReturn(meta);

        mockMvc.perform(post(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldName\":\"attachment\",\"contentType\":\"application/octet-stream\",\"totalBytes\":5}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(session.uploadId()))
                .andExpect(jsonPath("$.chunkCount").value(1));

        mockMvc.perform(put(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs/uploads/"
                                + session.uploadId()
                                + "/chunks/0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("hello")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(1));

        mockMvc.perform(post(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs/uploads/"
                                + session.uploadId()
                                + "/complete")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobId").value("blob-c1"));
    }

    @Test
    void chunkedUploadAbort() throws Exception {
        when(effective.runtimeEntity(isNull(), eq("demo-ticket"))).thenReturn(Optional.of(tableTicket()));
        when(tableStore.findById(any(), eq("t-1"), isNull())).thenReturn(Optional.of(Map.of("ticketId", "t-1")));
        String uploadId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        ChunkedBlobUploadSession session = new ChunkedBlobUploadSession(
                uploadId,
                "",
                "demo-ticket",
                "t-1",
                "attachment",
                "application/octet-stream",
                10,
                256 * 1024,
                1,
                Set.of(),
                Instant.parse("2026-10-15T14:00:00Z"));
        when(chunkedUploads.find(uploadId)).thenReturn(Optional.of(session));

        mockMvc.perform(delete(JsonApi.BASE + "/entities/demo-ticket/records/t-1/blobs/uploads/" + uploadId)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());
    }

    private static RenderedEntity tableTicket() {
        return new EntityRenderer()
                .render(
                        """
                        entityKey: demo-ticket
                        tableName: demo_ticket
                        version: 1
                        permission: page.read
                        storageMode: table
                        fields:
                          - name: ticketId
                            kind: text
                            required: true
                            maxLength: 64
                          - name: title
                            kind: text
                            required: true
                            maxLength: 200
                        """);
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        /** SQL PolicyEngine for WebMvcTest AuthZ-1f — 切片测用 sql 引擎。 */
        @Bean
        PolicyEngine policyEngine(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
            return PolicyEngineFactory.create("sql", tenantGuard, tenantAccess);
        }
    }
}
