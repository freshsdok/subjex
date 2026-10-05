package com.subjex.platform.app.deploy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DeployPage.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    ManifestCatalog.class,
    DeployPageTest.GateConfiguration.class
})
class DeployPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void yamlProbePathsAndNotAppliedSentence() throws Exception {
        assertClasspathMatchesDeployFiles();
        List<WorkloadManifest> rows = ManifestCatalog.load();
        assertEquals(List.of("platform-app", "sample-consumer"), rows.stream().map(WorkloadManifest::name).toList());
        for (WorkloadManifest row : rows) {
            assertEquals("/actuator/health/liveness", row.livenessPath());
            assertEquals("/actuator/health/readiness", row.readinessPath());
            assertTrue(row.memoryLimit().endsWith("Mi"), row.memoryLimit());
            assertFalse(row.image().isBlank());
        }

        String html = DeployPage.html(rows);
        assertTrue(html.contains(DeployPage.NOT_APPLIED_ZH));
        assertTrue(html.contains(DeployPage.NOT_APPLIED_EN));
        assertTrue(html.contains("platform-app"));
        assertTrue(html.contains("sample-consumer"));
        assertTrue(html.contains("subjex/platform-app:0.1.0-SNAPSHOT"));
        assertTrue(html.contains("subjex/sample-consumer:0.1.0-SNAPSHOT"));
        assertTrue(html.contains("/actuator/health/liveness"));
        assertTrue(html.contains("/actuator/health/readiness"));
        assertTrue(html.contains("512 兆比字节 (512Mi)"));
        assertTrue(html.contains("512 mebibytes (512Mi)"));
        assertTrue(html.contains("存活"));
        assertTrue(html.contains("就绪"));
        assertTrue(html.contains("liveness"));
        assertTrue(html.contains("readiness"));
        assertTrue(html.contains("占位，未构建镜像"));
        assertFalse(html.contains("<button"));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("kubectl"));
        assertFalse(html.contains("HorizontalPodAutoscaler"));
        assertFalse(html.contains("Running"));
    }

    @Test
    void pageRequiresTheOperatorAndDoesNotApply() throws Exception {
        mockMvc.perform(get("/deploy")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/deploy").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(DeployPage.NOT_APPLIED_EN)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("platform-app")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sample-consumer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/actuator/health/liveness")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/actuator/health/readiness")));
        mockMvc.perform(post("/deploy").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    private static void assertClasspathMatchesDeployFiles() throws Exception {
        Path dir = manifestDir();
        for (String name : ManifestCatalog.RESOURCE_NAMES) {
            byte[] fromDisk = Files.readAllBytes(dir.resolve(name));
            try (var in = DeployPageTest.class.getResourceAsStream("/k8s/" + name)) {
                assertTrue(in != null, name);
                assertArrayEquals(fromDisk, in.readAllBytes(), name);
            }
        }
    }

    private static Path manifestDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deploy/k8s");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deploy/k8s was not found");
    }


    @Test
    void languageAndSkinChangeTheChromeOnly() throws Exception {
        mockMvc.perform(get("/deploy").param("lang", "en").param("skin", "high-contrast")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Deploy manifests</title>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("lang=\"en\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-skin=\"high-contrast\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("这些清单没有应用到任何集群。")));
        mockMvc.perform(get("/deploy").cookie(new jakarta.servlet.http.Cookie("skin", "calm"))
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-skin=\"calm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>部署清单</title>")));
    }

    @TestConfiguration
    static class GateConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
