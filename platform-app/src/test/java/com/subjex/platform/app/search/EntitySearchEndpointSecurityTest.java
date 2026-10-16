package com.subjex.platform.app.search;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.flags.FeatureFlagKeys;
import com.subjex.platform.app.flags.FeatureFlagService;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * EntitySearchEndpointSecurityTest — page.read + tenant grant; read-only.
 */
@WebMvcTest(controllers = EntitySearchEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    EntitySearchEndpointSecurityTest.GuardConfiguration.class
})
class EntitySearchEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EntityRecordSearchStore store;

    @MockitoBean
    private FeatureFlagService featureFlags;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @BeforeEach
    void grantAllTenants() {
        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, OperatorTenantAccess.ALL_TENANTS);
        when(featureFlags.isEnabled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
    }

    @Test
    void unauthenticated401() throws Exception {
        mockMvc.perform(get(EntitySearchEndpoint.PATH).param("q", "x").param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void searchHappyPath() throws Exception {
        when(store.search(eq("acme"), isNull(), eq("Alpha"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(new EntityRecordSearchHit(
                        "acme", "asset", "a-1", "ACTIVE", "{\"title\":\"Alpha\"}", "2026-10-16")));
        mockMvc.perform(get(EntitySearchEndpoint.PATH)
                        .param("q", "Alpha")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].recordId").value("a-1"))
                .andExpect(jsonPath("$.tenantId").value("acme"));
        verify(store).search(eq("acme"), isNull(), eq("Alpha"), isNull(), isNull(), anyInt());
    }

    @Test
    void headerTenantAccepted() throws Exception {
        when(store.search(anyString(), anyString(), anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());
        mockMvc.perform(get(EntitySearchEndpoint.PATH)
                        .param("q", "x")
                        .param("entityKey", "asset")
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());
    }


    @Test
    void searchDisabledByFlagReturns404() throws Exception {
        when(featureFlags.isEnabled(eq("acme"), eq(FeatureFlagKeys.SEARCH_RECORDS), eq(true)))
                .thenReturn(false);
        mockMvc.perform(get(EntitySearchEndpoint.PATH)
                        .param("q", "Alpha")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(store);
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
