package com.subjex.platform.app.uiauthz;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.declaration.InternalDeclarationGit;
import com.subjex.platform.app.declaration.JdbcDeclarationStore;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UiPermissionDraftApiSecurityTest — PC-6a/6b：草稿 baseline/GET/PUT/promote/export 权限与校验。
 */
@WebMvcTest(controllers = UiPermissionDraftEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    UiPermissionDraftApiSecurityTest.SliceConfiguration.class
})
class UiPermissionDraftApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(UiPermissionDraftEndpoint.PATH + "/baseline"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanReadBaselineButNotWriteOrPromote() throws Exception {
        mockMvc.perform(get(UiPermissionDraftEndpoint.PATH + "/baseline")
                        .queryParam("client", "console")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client").value("console"))
                .andExpect(jsonPath("$.entries.length()").value(49));
        mockMvc.perform(put(UiPermissionDraftEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"client\":\"console\",\"defaults\":{\"lockedMenuEffect\":\"grey\",\"missingButtonEffect\":\"disable\"},\"entries\":[{\"id\":\"nav.overview\",\"kind\":\"menu\",\"href\":\"/\"}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(UiPermissionDraftEndpoint.PATH + "/promote")
                        .queryParam("client", "console")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanSaveAndReloadDraft() throws Exception {
        String body =
                """
                {
                  "version": 1,
                  "client": "console",
                  "defaults": {"lockedMenuEffect": "grey", "missingButtonEffect": "disable"},
                  "entries": [
                    {"id": "nav.overview", "kind": "menu", "href": "/"},
                    {"id": "btn.org.write", "kind": "button", "permission": "org.write"}
                  ]
                }
                """;
        mockMvc.perform(put(UiPermissionDraftEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").isNumber())
                .andExpect(jsonPath("$.draftState").value("DRAFT"))
                .andExpect(jsonPath("$.manifest.entries.length()").value(2));
        mockMvc.perform(get(UiPermissionDraftEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.entries[1].id").value("btn.org.write"));
    }

    @Test
    void inventPermissionIsRejected() throws Exception {
        String body =
                """
                {
                  "version": 1,
                  "client": "console",
                  "defaults": {"lockedMenuEffect": "grey", "missingButtonEffect": "disable"},
                  "entries": [
                    {"id": "nav.evil", "kind": "menu", "href": "/evil", "permission": "not.real"}
                  ]
                }
                """;
        mockMvc.perform(put(UiPermissionDraftEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operatorCanPromoteAndExportEffectiveYaml() throws Exception {
        String body =
                """
                {
                  "version": 1,
                  "client": "console",
                  "defaults": {"lockedMenuEffect": "grey", "missingButtonEffect": "disable"},
                  "entries": [
                    {"id": "nav.overview", "kind": "menu", "href": "/"},
                    {"id": "nav.org", "kind": "menu", "href": "/org", "permission": "org.read"},
                    {"id": "btn.org.write", "kind": "button", "permission": "org.write"}
                  ]
                }
                """;
        mockMvc.perform(put(UiPermissionDraftEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").isNumber());
        mockMvc.perform(post(UiPermissionDraftEndpoint.PATH + "/promote")
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ui-authz"))
                .andExpect(jsonPath("$.declarationKey").value("console"))
                .andExpect(jsonPath("$.revision").isNumber())
                .andExpect(jsonPath("$.gitCommitSha").isNotEmpty());
        String yaml = mockMvc.perform(get(UiPermissionDraftEndpoint.PATH + "/export")
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("client: console"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("nav.org"));
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        UiPermissionManifestCatalog uiPermissionManifestCatalog() {
            return new UiPermissionManifestCatalog();
        }

        @Bean
        JdbcDeclarationStore jdbcDeclarationStore(JdbcTemplate jdbc) {
            return new JdbcDeclarationStore(jdbc, Clock.systemUTC());
        }

        @Bean
        InternalDeclarationGit internalDeclarationGit() {
            return new InternalDeclarationGit();
        }

        @Bean
        TransactionTemplate transactionTemplate(DataSource operatorTables) {
            return new TransactionTemplate(new DataSourceTransactionManager(operatorTables));
        }

        @Bean
        UiPermissionDraftService uiPermissionDraftService(
                JdbcDeclarationStore store,
                UiPermissionManifestCatalog catalog,
                InternalDeclarationGit git,
                TransactionTemplate transactions)
                throws Exception {
            Path dir = Files.createTempDirectory("ui-authz-git");
            return new UiPermissionDraftService(store, catalog, git, dir, transactions);
        }
    }
}
