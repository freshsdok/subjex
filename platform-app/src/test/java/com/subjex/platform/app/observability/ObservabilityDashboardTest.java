package com.subjex.platform.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ObservabilityDashboardTest — WP9-2 看板文件测试：校验仓库内的 Grafana JSON 与抓取样例，不启动 Grafana，也不连接集群。
 * <p>
 * Checks the versioned dashboard pack operators can import into their own Grafana OSS. This is not a live
 * observability deploy and must not require Grafana Cloud.
 * 检查运维可导入自建 Grafana OSS 的版本化看板套件。这不是一次真实的可观测部署，也不要求 Grafana Cloud。
 */
class ObservabilityDashboardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> EXPECTED_METRICS = List.of(
            "up",
            "process_uptime_seconds",
            "process_cpu_usage",
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_bucket",
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_gc_pause_seconds_sum",
            "jvm_threads_live_threads",
            "hikaricp_connections_active",
            "logback_events_total",
            "tomcat_threads_busy_threads");

    @Test
    void dashboardIsImportableAndGroundedInExistingMetrics() throws Exception {
        Path dir = observabilityDir();
        String raw = Files.readString(dir.resolve("grafana/subjex-platform-overview.json"));
        JsonNode root = MAPPER.readTree(raw);

        assertEquals("subjex-platform-overview", root.path("uid").asText());
        assertTrue(root.path("title").asText().contains("Subjex platform overview"));
        assertEquals(39, root.path("schemaVersion").asInt());

        JsonNode inputs = root.path("__inputs");
        assertTrue(inputs.isArray() && inputs.size() >= 1);
        assertEquals("DS_PROMETHEUS", inputs.get(0).path("name").asText());
        assertEquals("prometheus", inputs.get(0).path("pluginId").asText());

        JsonNode panels = root.path("panels");
        assertTrue(panels.isArray() && panels.size() >= 10, "expected a usable set of operator panels");

        Set<Integer> ids = new HashSet<>();
        StringBuilder exprs = new StringBuilder();
        boolean sawTextPanel = false;
        for (JsonNode panel : panels) {
            int id = panel.path("id").asInt();
            assertTrue(id > 0, "panel id must be a positive integer");
            assertTrue(ids.add(id), "duplicate panel id: " + id);
            assertFalse(panel.path("title").asText().isBlank(), "panel title must not be blank");
            if ("text".equals(panel.path("type").asText())) {
                sawTextPanel = true;
                String content = panel.path("options").path("content").asText();
                assertTrue(content.contains("/audit"));
                assertTrue(content.contains("/api/v1/audit/export"));
                assertTrue(content.contains("/dead-letters"));
                assertTrue(content.contains("correlationId"));
                assertTrue(content.contains("Grafana Cloud"));
            }
            for (JsonNode target : panel.path("targets")) {
                exprs.append(target.path("expr").asText()).append('\n');
            }
        }
        assertTrue(sawTextPanel, "dashboard should point operators at existing audit/health/dead-letter views");

        String exprText = exprs.toString();
        for (String metric : EXPECTED_METRICS) {
            assertTrue(exprText.contains(metric), "missing stock metric " + metric);
        }
        assertTrue(exprText.contains("job=~\"$job\"") || exprText.contains("job=~'$job'"));

        String lower = raw.toLowerCase();
        assertFalse(lower.contains("grafana.net"));
        assertFalse(lower.contains("grafana-cloud"));
        assertFalse(lower.contains("a8c.") || lower.contains("grafana.com"));
    }

    @Test
    void scrapeExampleTargetsManagementPortsOnly() throws Exception {
        Path dir = observabilityDir();
        String scrape = Files.readString(dir.resolve("prometheus-scrape.example.yml"));
        String readme = Files.readString(dir.resolve("README.md"));

        assertTrue(scrape.contains("job_name: platform-app"));
        assertTrue(scrape.contains("job_name: entry-gateway"));
        assertTrue(scrape.contains("job_name: sample-consumer"));
        assertTrue(scrape.contains("metrics_path: /actuator/prometheus"));
        assertTrue(scrape.contains("platform-app:8089"));
        assertTrue(scrape.contains("entry-gateway:8090"));
        assertTrue(scrape.contains("sample-consumer:8091"));
        assertFalse(scrape.contains(":8080"));
        assertFalse(scrape.contains(":8088"));

        assertTrue(readme.contains("production cluster") && readme.contains("生产集群"));
        assertTrue(readme.contains("Grafana Cloud"));
        assertTrue(readme.contains("WP9-3") || readme.contains("replicas: 1"));
        assertTrue(readme.contains("/admin/health"));
        assertTrue(readme.contains("/api/v1/audit/export"));
        assertTrue(readme.contains("audit-ndjson-schema.md"));
    }

    @Test
    void progressNoteRecordsWp92AndLeavesWp93ForLater() throws Exception {
        Path note = repoRoot().resolve("docs/observability/wp9-observability-edge-progress.md");
        String text = Files.readString(note);
        assertTrue(text.contains("WP9-2 [done]"));
        assertTrue(text.contains("deploy/observability"));
        assertTrue(text.contains("subjex-platform-overview.json"));
        assertTrue(text.contains("WP9-3"));
        assertTrue(text.contains("Do **not** start WP9-3 in this slice")
                || text.contains("不启动 WP9-3")
                || text.contains("Do **not** start WP9-3"));
        assertTrue(text.contains("不是生产集群") || text.toLowerCase().contains("not a production cluster"));
    }

    private static Path observabilityDir() {
        Path dir = repoRoot().resolve("deploy/observability");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("deploy/observability was not found");
        }
        return dir;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("deploy/observability"))
                    && Files.isDirectory(dir.resolve("deploy/k8s"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root was not found");
    }
}
