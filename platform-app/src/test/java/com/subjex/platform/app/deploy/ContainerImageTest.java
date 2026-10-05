package com.subjex.platform.app.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ContainerImageTest — 镜像文件测试：只读 Dockerfile 和清单的文字，不构建镜像，也不需要 Docker。
 * <p>
 * Each process has a multi-stage Dockerfile that runs as a non-root user, and its manifest names the image that
 * Dockerfile is tagged with.
 * 每个进程有一份多阶段 Dockerfile，以非 root 用户运行；它的清单写的镜像名就是这份 Dockerfile 打的标签。
 */
class ContainerImageTest {

    @ParameterizedTest
    @ValueSource(strings = {"platform-app", "sample-consumer", "entry-gateway"})
    void dockerfileIsMultiStageNonRootAndMatchesTheManifest(String workload) throws Exception {
        Path root = repositoryRoot();
        String dockerfile = Files.readString(root.resolve(workload).resolve("Dockerfile"));
        String manifest = Files.readString(root.resolve("deploy/k8s").resolve(workload + ".yaml"));

        List<String> stages = dockerfile.lines().filter(line -> line.startsWith("FROM ")).toList();
        assertEquals(2, stages.size(), "one build stage and one runtime stage");
        assertTrue(stages.get(0).contains(" AS build"));
        assertTrue(dockerfile.contains("COPY --from=build"));
        assertTrue(dockerfile.contains("-pl " + workload + " -am package"));

        Matcher user = Pattern.compile("(?m)^USER (\\S+)").matcher(dockerfile);
        assertTrue(user.find(), "a USER line");
        String runAs = user.group(1);
        assertFalse(runAs.equals("root") || runAs.startsWith("0:") || runAs.equals("0"), runAs);

        Matcher tag = Pattern.compile("-t (subjex/" + workload + ":\\S+) \\.").matcher(dockerfile);
        assertTrue(tag.find(), "the build comment names the image tag");
        assertTrue(manifest.contains("image: " + tag.group(1)), "manifest uses " + tag.group(1));
        assertTrue(manifest.contains("runAsNonRoot: true"));
        assertTrue(manifest.contains("runAsUser: " + runAs.split(":")[0]));
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("deploy/k8s"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root was not found");
    }
}
