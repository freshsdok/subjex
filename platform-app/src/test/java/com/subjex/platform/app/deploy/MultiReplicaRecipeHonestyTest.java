package com.subjex.platform.app.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * MultiReplicaRecipeHonestyTest — WP9-3 门禁：platform-app 配方只有在两个共享 JDBC 后端都为 jdbc 时，才可以宣称 replicas&gt;1。
 * <p>
 * Parses k8s and Compose recipe files only. It does not call {@code kubectl}, does not start Compose,
 * and does not apply anything to a cluster. This machine is not a production cluster.
 * 只解析 Kubernetes 与 Compose 配方文件。不调用 kubectl，不启动 Compose，也不向集群应用任何清单。本机不是生产集群。
 */
class MultiReplicaRecipeHonestyTest {

    private static final String RATE_LIMIT_ENV = "PLATFORM_RATE_LIMIT_BACKEND";
    private static final String CIRCUIT_BREAKER_ENV = "PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND";

    @Test
    void defaultPublishedRecipesStaySingleReplica() throws Exception {
        Recipe defaultK8s = parse(repoFile("deploy/k8s/platform-app.yaml"));
        assertEquals(1, defaultK8s.replicas);
        assertNull(defaultK8s.rateLimitBackend, "default manifest must not flip rate-limit to jdbc");
        assertNull(defaultK8s.circuitBreakerBackend, "default manifest must not flip circuit-breaker to jdbc");
        assertTrue(defaultK8s.honest(), "replicas: 1 is always honest");

        Recipe defaultCompose = parse(repoFile("deploy/compose/docker-compose.yml"));
        assertTrue(
                defaultCompose.replicas == null || defaultCompose.replicas == 1,
                "default compose must not advertise platform-app replicas>1");
        assertTrue(defaultCompose.honest());

        assertEquals(1, parse(repoFile("deploy/k8s/entry-gateway.yaml")).replicas);
        assertEquals(1, parse(repoFile("deploy/k8s/sample-consumer.yaml")).replicas);
    }

    @Test
    void officialMultiReplicaRecipesIncludeBothSharedJdbcBackends() throws Exception {
        Recipe snippet = parse(repoFile("deploy/k8s/platform-app-shared-backends.snippet.yaml"));
        assertEquals(2, snippet.replicas);
        assertEquals("jdbc", snippet.rateLimitBackend);
        assertEquals("jdbc", snippet.circuitBreakerBackend);
        assertTrue(snippet.honest(), "optional k8s snippet must be an honest N>1 recipe");

        Recipe composeOverride = parse(repoFile("deploy/compose/docker-compose.shared-backends.yml"));
        assertEquals(2, composeOverride.replicas);
        assertEquals("jdbc", composeOverride.rateLimitBackend);
        assertEquals("jdbc", composeOverride.circuitBreakerBackend);
        assertTrue(composeOverride.honest(), "optional compose override must be an honest N>1 recipe");
    }

    @Test
    void repoRecipesDoNotAdvertiseDishonestPlatformAppReplicas() throws Exception {
        List<String> dishonest = new ArrayList<>();
        for (Path file : recipeFiles()) {
            Recipe recipe = parse(file);
            if (!recipe.honest()) {
                dishonest.add(rel(file) + " -> " + recipe);
            }
        }
        assertTrue(dishonest.isEmpty(), () -> "dishonest replicas>1 recipes:\n" + String.join("\n", dishonest));
    }

    @Test
    void dishonestRecipesAreRejected() {
        assertFalse(parseYaml("k8s-replicas-only", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 2
                """).honest());

        assertFalse(parseYaml("k8s-rate-limit-only", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 2
                  template:
                    spec:
                      containers:
                        - name: platform-app
                          env:
                            - name: PLATFORM_RATE_LIMIT_BACKEND
                              value: jdbc
                """).honest());

        assertFalse(parseYaml("k8s-circuit-breaker-only", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 3
                  template:
                    spec:
                      containers:
                        - name: platform-app
                          env:
                            - name: PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND
                              value: jdbc
                """).honest());

        assertFalse(parseYaml("k8s-process-backends", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 2
                  template:
                    spec:
                      containers:
                        - name: platform-app
                          env:
                            - name: PLATFORM_RATE_LIMIT_BACKEND
                              value: process
                            - name: PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND
                              value: process
                """).honest());

        assertFalse(parseYaml("k8s-mixed-backends", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 2
                  template:
                    spec:
                      containers:
                        - name: platform-app
                          env:
                            - name: PLATFORM_RATE_LIMIT_BACKEND
                              value: jdbc
                            - name: PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND
                              value: process
                """).honest());

        assertFalse(parseYaml("compose-scale-without-backends", """
                services:
                  platform-app:
                    deploy:
                      replicas: 2
                """).honest());

        assertFalse(parseYaml("compose-scale-key-without-backends", """
                services:
                  platform-app:
                    scale: 2
                """).honest());

        assertFalse(parseYaml("compose-one-backend", """
                services:
                  platform-app:
                    environment:
                      PLATFORM_RATE_LIMIT_BACKEND: jdbc
                    deploy:
                      replicas: 2
                """).honest());

        // replicas: 1, or jdbc on a single replica, remains honest.
        // 单副本，或在单副本上打开 jdbc，都仍然诚实。
        assertTrue(parseYaml("k8s-single", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 1
                """).honest());

        assertTrue(parseYaml("k8s-single-with-jdbc", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 1
                  template:
                    spec:
                      containers:
                        - name: platform-app
                          env:
                            - name: PLATFORM_RATE_LIMIT_BACKEND
                              value: jdbc
                            - name: PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND
                              value: jdbc
                """).honest());

        assertTrue(parseYaml("k8s-honest-n2", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: platform-app
                spec:
                  replicas: 2
                  template:
                    spec:
                      containers:
                        - name: platform-app
                          env:
                            - name: PLATFORM_RATE_LIMIT_BACKEND
                              value: jdbc
                            - name: PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND
                              value: jdbc
                """).honest());

        assertTrue(parseYaml("compose-honest-n2", """
                services:
                  platform-app:
                    environment:
                      PLATFORM_RATE_LIMIT_BACKEND: jdbc
                      PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND: jdbc
                    deploy:
                      replicas: 2
                """).honest());
    }

    private static Recipe parse(Path file) throws Exception {
        return parseYaml(rel(file), Files.readString(file));
    }

    private static Recipe parseYaml(String source, String raw) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Recipe merged = Recipe.empty(source);
        for (Object document : yaml.loadAll(raw)) {
            if (document == null) {
                continue;
            }
            merged = merged.merge(inspect(source, document));
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Recipe inspect(String source, Object document) {
        if (!(document instanceof Map<?, ?> rawMap)) {
            return Recipe.empty(source);
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        if (map.containsKey("services")) {
            return inspectCompose(source, map);
        }
        return inspectKubernetes(source, map);
    }

    @SuppressWarnings("unchecked")
    private static Recipe inspectKubernetes(String source, Map<String, Object> doc) {
        String kind = stringVal(doc.get("kind"));
        Map<String, Object> metadata = map(doc.get("metadata"));
        String name = metadata == null ? null : stringVal(metadata.get("name"));
        boolean platformApp = "platform-app".equals(name)
                || (name == null && source.contains("platform-app") && "Deployment".equals(kind));
        if (!platformApp) {
            // Other processes (entry-gateway, sample-consumer) are recorded only when named.
            // 其他进程仅在带名字时记录，不套用 platform-app 的 JDBC 门禁。
            Integer replicas = null;
            if ("Deployment".equals(kind)) {
                Map<String, Object> spec = map(doc.get("spec"));
                replicas = spec == null ? null : intVal(spec.get("replicas"));
            }
            return new Recipe(source, name, replicas, null, null, false);
        }
        Map<String, Object> spec = map(doc.get("spec"));
        Integer replicas = spec == null ? null : intVal(spec.get("replicas"));
        Env env = readKubernetesEnv(spec);
        return new Recipe(source, "platform-app", replicas, env.rateLimit, env.circuitBreaker, true);
    }

    @SuppressWarnings("unchecked")
    private static Recipe inspectCompose(String source, Map<String, Object> doc) {
        Map<String, Object> services = map(doc.get("services"));
        if (services == null || !services.containsKey("platform-app")) {
            return Recipe.empty(source);
        }
        Map<String, Object> service = map(services.get("platform-app"));
        if (service == null) {
            return Recipe.empty(source);
        }
        Integer replicas = intVal(service.get("scale"));
        Map<String, Object> deploy = map(service.get("deploy"));
        if (replicas == null && deploy != null) {
            replicas = intVal(deploy.get("replicas"));
        }
        Env env = readComposeEnv(service.get("environment"));
        return new Recipe(source, "platform-app", replicas, env.rateLimit, env.circuitBreaker, true);
    }

    @SuppressWarnings("unchecked")
    private static Env readKubernetesEnv(Map<String, Object> spec) {
        if (spec == null) {
            return Env.EMPTY;
        }
        Map<String, Object> template = map(spec.get("template"));
        Map<String, Object> podSpec = template == null ? null : map(template.get("spec"));
        List<Object> containers = podSpec == null ? null : list(podSpec.get("containers"));
        if (containers == null) {
            return Env.EMPTY;
        }
        String rateLimit = null;
        String circuitBreaker = null;
        for (Object container : containers) {
            Map<String, Object> c = map(container);
            if (c == null) {
                continue;
            }
            List<Object> env = list(c.get("env"));
            if (env == null) {
                continue;
            }
            for (Object entry : env) {
                Map<String, Object> item = map(entry);
                if (item == null) {
                    continue;
                }
                String name = stringVal(item.get("name"));
                String value = stringVal(item.get("value"));
                if (RATE_LIMIT_ENV.equals(name)) {
                    rateLimit = value;
                } else if (CIRCUIT_BREAKER_ENV.equals(name)) {
                    circuitBreaker = value;
                }
            }
        }
        return new Env(rateLimit, circuitBreaker);
    }

    @SuppressWarnings("unchecked")
    private static Env readComposeEnv(Object environment) {
        if (environment instanceof Map<?, ?> map) {
            return new Env(stringVal(map.get(RATE_LIMIT_ENV)), stringVal(map.get(CIRCUIT_BREAKER_ENV)));
        }
        if (environment instanceof List<?> list) {
            String rateLimit = null;
            String circuitBreaker = null;
            for (Object item : list) {
                if (item instanceof String line) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String name = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (RATE_LIMIT_ENV.equals(name)) {
                        rateLimit = value;
                    } else if (CIRCUIT_BREAKER_ENV.equals(name)) {
                        circuitBreaker = value;
                    }
                } else if (item instanceof Map<?, ?> entry) {
                    if (entry.containsKey(RATE_LIMIT_ENV)) {
                        rateLimit = stringVal(entry.get(RATE_LIMIT_ENV));
                    }
                    if (entry.containsKey(CIRCUIT_BREAKER_ENV)) {
                        circuitBreaker = stringVal(entry.get(CIRCUIT_BREAKER_ENV));
                    }
                }
            }
            return new Env(rateLimit, circuitBreaker);
        }
        return Env.EMPTY;
    }

    private static List<Path> recipeFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        Path root = repoRoot();
        try (Stream<Path> k8s = Files.list(root.resolve("deploy/k8s"))) {
            k8s.filter(p -> {
                        String name = p.getFileName().toString();
                        return Files.isRegularFile(p) && (name.endsWith(".yaml") || name.endsWith(".yml"));
                    })
                    .forEach(files::add);
        }
        try (Stream<Path> compose = Files.list(root.resolve("deploy/compose"))) {
            compose.filter(p -> {
                        String name = p.getFileName().toString();
                        return Files.isRegularFile(p) && (name.endsWith(".yaml") || name.endsWith(".yml"));
                    })
                    .forEach(files::add);
        }
        assertFalse(files.isEmpty(), "expected k8s/compose recipe files");
        return files;
    }

    private static Path repoFile(String relative) {
        Path file = repoRoot().resolve(relative);
        assertTrue(Files.isRegularFile(file), () -> "missing " + relative);
        return file;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("deploy/k8s/platform-app.yaml"))
                    && Files.isRegularFile(dir.resolve("deploy/compose/docker-compose.yml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root was not found");
    }

    private static String rel(Path file) {
        return repoRoot().relativize(file).toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.trim());
        }
        return null;
    }

    /**
     * Parsed platform-app (or other named) replica recipe. / 解析出的 platform-app（或其他已命名）副本配方。
     *
     * @param gated {@code true} when this document is a platform-app recipe and the JDBC honesty gate applies
     */
    private record Recipe(
            String source,
            String name,
            Integer replicas,
            String rateLimitBackend,
            String circuitBreakerBackend,
            boolean gated) {

        static Recipe empty(String source) {
            return new Recipe(source, null, null, null, null, false);
        }

        boolean honest() {
            if (!gated) {
                return true;
            }
            if (replicas == null || replicas <= 1) {
                return true;
            }
            return "jdbc".equalsIgnoreCase(rateLimitBackend)
                    && "jdbc".equalsIgnoreCase(circuitBreakerBackend);
        }

        Recipe merge(Recipe other) {
            if (other == null || (other.name == null && other.replicas == null && !other.gated)) {
                return this;
            }
            if (!this.gated && !other.gated) {
                return other.replicas != null ? other : this;
            }
            if (this.gated && other.gated) {
                return new Recipe(
                        source,
                        "platform-app",
                        other.replicas != null ? other.replicas : replicas,
                        other.rateLimitBackend != null ? other.rateLimitBackend : rateLimitBackend,
                        other.circuitBreakerBackend != null ? other.circuitBreakerBackend : circuitBreakerBackend,
                        true);
            }
            return other.gated ? other : this;
        }

        @Override
        public String toString() {
            return "replicas="
                    + replicas
                    + " rateLimit="
                    + rateLimitBackend
                    + " circuitBreaker="
                    + circuitBreakerBackend;
        }
    }

    private record Env(String rateLimit, String circuitBreaker) {
        static final Env EMPTY = new Env(null, null);
    }
}
