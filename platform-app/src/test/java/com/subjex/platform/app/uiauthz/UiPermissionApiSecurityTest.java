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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * UiPermissionApiSecurityTest — GET ui-permissions：401；classpath 默认；晋升后读 PROMOTED 叠层。
 */
@WebMvcTest(controllers = {UiPermissionEndpoint.class, UiPermissionDraftEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    UiPermissionApiSecurityTest.SliceConfiguration.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UiPermissionApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Order(1)
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(UiPermissionEndpoint.PATH).queryParam("client", "console"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void operatorGetsClasspathManifestWithAllowedBeforePromote() throws Exception {
        mockMvc.perform(get(UiPermissionEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.client").value("console"))
                .andExpect(jsonPath("$.defaults.lockedMenuEffect").value("grey"))
                .andExpect(jsonPath("$.defaults.missingButtonEffect").value("disable"))
                .andExpect(jsonPath("$.entries.length()").value(22))
                .andExpect(jsonPath("$.entries[0].id").value("nav.overview"))
                .andExpect(jsonPath("$.entries[0].kind").value("menu"))
                .andExpect(jsonPath("$.entries[0].href").value("/"))
                .andExpect(jsonPath("$.entries[0].allowed").value(true))
                .andExpect(jsonPath("$.entries[5].id").value("nav.inbox.compose"))
                .andExpect(jsonPath("$.entries[5].allowed").value(true))
                .andExpect(jsonPath("$.entries[17].id").value("nav.org"))
                .andExpect(jsonPath("$.entries[17].permission").value("org.read"))
                .andExpect(jsonPath("$.entries[17].allowed").value(true))
                .andExpect(jsonPath("$.entries[20].id").value("btn.inbox.compose.send"))
                .andExpect(jsonPath("$.entries[20].kind").value("button"))
                .andExpect(jsonPath("$.entries[20].allowed").value(true))
                .andExpect(jsonPath("$.entries[21].id").value("btn.org.write"))
                .andExpect(jsonPath("$.entries[21].allowed").value(true));
    }

    @Test
    @Order(3)
    void viewerGetsAllowedFalseForComposeWithoutSendPermission() throws Exception {
        mockMvc.perform(get(UiPermissionEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[4].id").value("nav.inbox"))
                .andExpect(jsonPath("$.entries[4].allowed").value(true))
                .andExpect(jsonPath("$.entries[5].id").value("nav.inbox.compose"))
                .andExpect(jsonPath("$.entries[5].allowed").value(false))
                .andExpect(jsonPath("$.entries[6].id").value("nav.inbox.groups"))
                .andExpect(jsonPath("$.entries[6].allowed").value(false))
                .andExpect(jsonPath("$.entries[0].allowed").value(true));
    }

    @Test
    @Order(4)
    void defaultClientIsConsole() throws Exception {
        mockMvc.perform(get(UiPermissionEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client").value("console"));
    }

    @Test
    @Order(5)
    void unknownClientGets400() throws Exception {
        mockMvc.perform(get(UiPermissionEndpoint.PATH)
                        .queryParam("client", "no-such-client")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").exists());
    }

    @Test
    @Order(6)
    void afterPromoteLiveGetServesPromotedBindingsAndAllowedFlags() throws Exception {
        // Promote a slim Manifest: overview (open) + compose (needs inbox.send.system) + org.write button.
        // 晋升精简 Manifest：概览（开放）+ 撰写（需 inbox.send.system）+ org.write 按钮。
        String body =
                """
                {
                  "version": 1,
                  "client": "console",
                  "defaults": {"lockedMenuEffect": "grey", "missingButtonEffect": "disable"},
                  "entries": [
                    {"id": "nav.overview", "kind": "menu", "href": "/"},
                    {"id": "nav.inbox.compose", "kind": "menu", "href": "/inbox/compose", "permission": "inbox.send.system"},
                    {"id": "btn.org.write", "kind": "button", "permission": "org.write"}
                  ]
                }
                """;
        mockMvc.perform(put(UiPermissionDraftEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        // Unauthorized cannot promote — 未授权不能晋升。
        mockMvc.perform(post(UiPermissionDraftEndpoint.PATH + "/promote")
                        .queryParam("client", "console")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(UiPermissionDraftEndpoint.PATH + "/promote")
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ui-authz"))
                .andExpect(jsonPath("$.gitCommitSha").isNotEmpty());
        // Live GET now reflects promoted layer (3 entries) — 线上 GET 现为晋升叠层。
        mockMvc.perform(get(UiPermissionEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].id").value("nav.overview"))
                .andExpect(jsonPath("$.entries[0].allowed").value(true))
                .andExpect(jsonPath("$.entries[1].id").value("nav.inbox.compose"))
                .andExpect(jsonPath("$.entries[1].allowed").value(true))
                .andExpect(jsonPath("$.entries[2].id").value("btn.org.write"))
                .andExpect(jsonPath("$.entries[2].allowed").value(true));
        // Viewer: compose + org.write denied by permission set — 只读员：撰写与写组织均不允许。
        mockMvc.perform(get(UiPermissionEndpoint.PATH)
                        .queryParam("client", "console")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].allowed").value(true))
                .andExpect(jsonPath("$.entries[1].allowed").value(false))
                .andExpect(jsonPath("$.entries[2].allowed").value(false));
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
            Path dir = Files.createTempDirectory("ui-authz-live-git");
            return new UiPermissionDraftService(store, catalog, git, dir, transactions);
        }
    }
}
