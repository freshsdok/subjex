package com.subjex.platform.app.deploy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * KubernetesManifestTest — 清单测试：只检查文件内容，不连接集群，也不构建镜像。
 */
class KubernetesManifestTest {

    @Test
    void manifestsDescribeBothProcessesAndAreNotAnAutoscaler() throws Exception {
        Path dir = manifestDir();
        String platform = Files.readString(dir.resolve("platform-app.yaml"));
        String consumer = Files.readString(dir.resolve("sample-consumer.yaml"));
        String readme = Files.readString(dir.resolve("README.md"));

        for (String manifest : new String[] {platform, consumer}) {
            assertTrue(manifest.contains("kind: Deployment"));
            assertTrue(manifest.contains("kind: Service"));
            assertTrue(manifest.contains("livenessProbe:"));
            assertTrue(manifest.contains("readinessProbe:"));
            assertTrue(manifest.contains("/actuator/health/liveness"));
            assertTrue(manifest.contains("/actuator/health/readiness"));
            assertTrue(manifest.contains("requests:"));
            assertTrue(manifest.contains("limits:"));
            assertFalse(manifest.contains("HorizontalPodAutoscaler"));
            assertFalse(manifest.contains("autoscaling/"));
        }
        assertTrue(platform.contains("name: platform-app"));
        assertTrue(consumer.contains("name: sample-consumer"));
        assertTrue(readme.contains("not applied"));
        assertTrue(readme.contains("不会被应用"));
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
}
