package com.subjex.platform.app.resourceevent;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.Totals;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ResourceEventSecurityTest — POST ingest is open; GET summary needs resource-event.read.
 * 上报接口开放；汇总读取需要 resource-event.read。
 */
@WebMvcTest(controllers = ResourceEventEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    ResourceEventSecurityTest.GuardConfiguration.class
})
class ResourceEventSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResourceEventService events;

    @Test
    void unauthenticatedCanPostIngest() throws Exception {
        when(events.record(any(), any(), any()))
                .thenReturn(new ResourceEventService.AcceptedEvent(
                        "ev-1",
                        "tenant-a",
                        "open",
                        "questionnaire",
                        "q-1",
                        Instant.parse("2026-11-23T04:00:00Z")));
        mockMvc.perform(post(ResourceEventEndpoint.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"questionnaire\",\"resourceId\":\"q-1\",\"eventType\":\"open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("ev-1"));
    }

    @Test
    void unauthenticatedSummaryGets401() throws Exception {
        mockMvc.perform(get(ResourceEventEndpoint.SUMMARY_PATH).queryParam("tenantId", "tenant-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readerCanReadSummary() throws Exception {
        when(events.summarise(any(), any(), isNull(), isNull()))
                .thenReturn(new ResourceEventService.SummaryDocument(
                        "tenant-a",
                        Instant.parse("2026-10-24T04:00:00Z"),
                        Instant.parse("2026-11-23T04:00:00Z"),
                        new Totals(0, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
        mockMvc.perform(get(ResourceEventEndpoint.SUMMARY_PATH)
                        .queryParam("tenantId", "tenant-a")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.shares").value(0));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
