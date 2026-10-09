package com.subjex.platform.app.capability;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AiSummarizePreviewConfirmE2ETest — AI-3d：ai.summarizePreview 预览 → 签票 → 写回一次成功 → 再写回失败。
 * <p>
 * Hard rule: no write-back without a consumed confirm ticket. Default sink is noop (persisted=false).
 * 硬规则：无确认票不得写回。默认落点 noop。
 */
@WebMvcTest(controllers = CapabilityApiEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    AiSummarizePreviewConfirmE2ETest.SliceConfiguration.class
})
class AiSummarizePreviewConfirmE2ETest {

    private static final String CAP = "ai.summarizePreview";
    private static final String BASE = CapabilityApiEndpoint.PATH + "/" + CAP;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperatorActionAudit audit;

    @Test
    void summarizePreview_run_ticket_writeBack_once_thenReject() throws Exception {
        String input = "{\"inputText\":\"e2e-summarize-body\"}";

        mockMvc.perform(post(BASE + "/run")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(input))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityId").value(CAP))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("e2e-summarize-body")));

        verify(audit, atLeastOnce())
                .record(any(), eq(CapabilityApiEndpoint.AUDIT_PREVIEW), eq(CAP), eq(AuditOutcome.ALLOWED));

        String ticketJson = mockMvc.perform(post(BASE + "/write-ticket")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(input))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.previewText").value(org.hamcrest.Matchers.containsString("e2e-summarize-body")))
                .andExpect(jsonPath("$.inputDigest").isNotEmpty())
                .andExpect(jsonPath("$.previewDigest").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode node = JSON.readTree(ticketJson);
        String writeBackBody = String.format(
                "{\"ticketId\":\"%s\",\"inputDigest\":\"%s\",\"previewDigest\":\"%s\",\"expiresAt\":\"%s\",\"previewText\":%s}",
                node.get("ticketId").asText(),
                node.get("inputDigest").asText(),
                node.get("previewDigest").asText(),
                node.get("expiresAt").asText(),
                node.get("previewText").toString());

        mockMvc.perform(post(BASE + "/write-back")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeBackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityId").value(CAP))
                .andExpect(jsonPath("$.ticketId").value(node.get("ticketId").asText()))
                .andExpect(jsonPath("$.sink").value("noop"))
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.containsString("e2e-summarize-body")));

        verify(audit, atLeastOnce())
                .record(
                        any(),
                        eq(CapabilityApiEndpoint.AUDIT_CONFIRM),
                        eq(CAP + "#" + node.get("ticketId").asText()),
                        eq(AuditOutcome.ALLOWED));

        mockMvc.perform(post(BASE + "/write-back")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeBackBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("already used")));
    }

    @Test
    void writeBackWithoutTicketBodyIsRejected() throws Exception {
        mockMvc.perform(post(BASE + "/write-back")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("required")));
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        CapabilityCatalog capabilityCatalog() {
            return new CapabilityCatalog();
        }

        @Bean
        ModelCompletionClient modelCompletionClient() {
            return new LocalStubModelCompletionClient();
        }

        @Bean
        CapabilityRunner capabilityRunner(
                CapabilityCatalog capabilityCatalog, ModelCompletionClient modelCompletionClient) {
            return new CapabilityRunner(capabilityCatalog, modelCompletionClient);
        }

        @Bean
        AiWriteConfirmGate aiWriteConfirmGate() {
            return new InMemoryAiWriteConfirmGate(
                    Clock.fixed(Instant.parse("2026-10-09T06:00:00Z"), ZoneOffset.UTC));
        }

        @Bean
        AiWriteBackSink aiWriteBackSink() {
            return new NoOpAiWriteBackSink();
        }
    }
}
