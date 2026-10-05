package com.subjex.platform.app.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * ManifestCatalog — 清单目录：从 classpath 上复制来的 YAML 读出工作负载。
 * <p>
 * The build copies {@code deploy/k8s} into {@code classpath:k8s}. The page does not
 * keep a second copy of the probe paths or the memory limit.
 * 构建把 {@code deploy/k8s} 复制到 {@code classpath:k8s}。页面不另存一份探针路径或内存上限。
 */
@Component
public class ManifestCatalog {

    static final List<String> RESOURCE_NAMES = List.of(
            "platform-app.yaml",
            "sample-consumer.yaml");

    public List<WorkloadManifest> list() {
        return load();
    }

    static List<WorkloadManifest> load() {
        List<WorkloadManifest> workloads = new ArrayList<>();
        for (String name : RESOURCE_NAMES) {
            workloads.addAll(read(name));
        }
        return List.copyOf(workloads);
    }

    private static List<WorkloadManifest> read(String resourceName) {
        String text = text(resourceName);
        List<WorkloadManifest> found = new ArrayList<>();
        for (Object document : new Yaml().loadAll(text)) {
            if (!(document instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> doc = strings(raw);
            if (!"Deployment".equals(String.valueOf(doc.get("kind")))) {
                continue;
            }
            found.add(workload(resourceName, doc));
        }
        if (found.isEmpty()) {
            throw new IllegalStateException(resourceName + " has no Deployment");
        }
        return found;
    }

    private static WorkloadManifest workload(String resourceName, Map<String, Object> doc) {
        Map<String, Object> metadata = map(doc.get("metadata"), resourceName + " metadata");
        String name = required(metadata.get("name"), resourceName + " metadata.name");
        Map<String, Object> container = firstContainer(resourceName, doc);
        String image = required(container.get("image"), name + " image");
        String liveness = probePath(container, "livenessProbe", name);
        String readiness = probePath(container, "readinessProbe", name);
        String memory = memoryLimit(container, name);
        return new WorkloadManifest(name, image, liveness, readiness, memory);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstContainer(String resourceName, Map<String, Object> doc) {
        Map<String, Object> spec = map(doc.get("spec"), resourceName + " spec");
        Map<String, Object> template = map(spec.get("template"), resourceName + " template");
        Map<String, Object> pod = map(template.get("spec"), resourceName + " pod spec");
        Object containers = pod.get("containers");
        if (!(containers instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException(resourceName + " has no container");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> raw)) {
            throw new IllegalStateException(resourceName + " container is not a map");
        }
        return strings(raw);
    }

    private static String probePath(Map<String, Object> container, String field, String name) {
        Map<String, Object> probe = map(container.get(field), name + " " + field);
        Map<String, Object> httpGet = map(probe.get("httpGet"), name + " " + field + ".httpGet");
        return required(httpGet.get("path"), name + " " + field + " path");
    }

    private static String memoryLimit(Map<String, Object> container, String name) {
        Map<String, Object> resources = map(container.get("resources"), name + " resources");
        Map<String, Object> limits = map(resources.get("limits"), name + " resources.limits");
        return required(limits.get("memory"), name + " memory limit");
    }

    private static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException(what + " is missing");
        }
        return strings(raw);
    }

    private static Map<String, Object> strings(Map<?, ?> raw) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static String required(Object value, String what) {
        if (value == null) {
            throw new IllegalStateException(what + " is missing");
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new IllegalStateException(what + " is blank");
        }
        return text;
    }

    private static String text(String resourceName) {
        String path = "/k8s/" + resourceName;
        try (InputStream in = ManifestCatalog.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
