package com.subjex.platform.app.entity;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


/**
 * GenericEntitySecurityTest — purpose: generic entity CRUD AuthZ via declaration permissions.
 * Gates: missing permission 403; tenantScoped requires X-Tenant-Id + grant; runtimeEntity overlay rules.
 * <p>
 * 目的：通用实体 CRUD 经声明权限鉴权。门禁：缺权限 403；租户隔离须头+授权；runtimeEntity 覆盖规则。
 */
@WebMvcTest(controllers = GenericEntityEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    GenericEntitySecurityTest.GuardConfiguration.class
})
class GenericEntitySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EffectiveDeclarationService effective;

    @MockitoBean
    private GenericEntityStore store;

    @MockitoBean
    private HybridEntityStore hybridStore;

    @MockitoBean
    private EntityBlobStore blobStore;

    @Test
    void tenantScopedEntityWithoutHeaderIsDenied() throws Exception {
        when(effective.runtimeEntity(isNull(), eq("scoped-item"))).thenReturn(Optional.of(scopedEntity()));
        mockMvc.perform(get(JsonApi.BASE + "/entities/scoped-item/records")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantScopedEntityWithGrantReturnsOk() throws Exception {
        when(effective.runtimeEntity(eq("acme"), eq("scoped-item"))).thenReturn(Optional.of(scopedEntity()));
        when(store.list(any(), anyInt(), isNull(), eq(true), isNull(), isNull(), eq("acme")))
                .thenReturn(List.of(Map.of("id", "1", "title", "Hi")));
        mockMvc.perform(get(JsonApi.BASE + "/entities/scoped-item/records")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "acme"))
                .andExpect(status().isOk());
        verify(store).list(any(), anyInt(), isNull(), eq(true), isNull(), isNull(), eq("acme"));
    }

    private static RenderedEntity scopedEntity() {
        return new EntityRenderer().render("""
                entityKey: scoped-item
                tableName: scoped_item
                version: 1
                permission: page.read
                tenantScoped: true
                storageMode: table
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: title
                    kind: text
                    required: true
                    maxLength: 64
                """);
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
