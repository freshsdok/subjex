package com.subjex.platform.app.deploy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * EdgeRecipeHonestyTest — 边缘配方门禁：解析 {@code deploy/edge/} 中的 Nginx / APISIX 配方，确认 TLS、限流、拦截 actuator，且未提交真实私钥或生产主机名。
 * <p>
 * Parses recipe files only. It does not start Nginx or APISIX, does not bind public ports, and does not
 * land TLS on a live edge. This machine is not a production cluster.
 * 只解析配方文件。不启动 Nginx 或 APISIX，不绑定对公端口，也不在真实边缘落地 TLS。本机不是生产集群。
 */
class EdgeRecipeHonestyTest {

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----\\s*([\\s\\S]*?)-----END \\1-----");
    private static final Pattern PLACEHOLDER = Pattern.compile("PLACEHOLDER_[A-Z0-9_]+");
    private static final Set<String> FILENAME_LABELS =
            Set.of("log", "pid", "inc", "conf", "crt", "key", "pem", "p12", "types", "md", "yaml", "yml", "txt");
    private static final Set<String> DOC_DOMAINS = Set.of("example.com", "example.net", "example.org");

    @Test
    void nginxRecipeTerminatesTlsLimitsAndBlocksActuator() throws Exception {
        String conf = Files.readString(edgeFile("nginx/subjex.conf"));
        String inc = Files.readString(edgeFile("nginx/subjex-proxy.inc"));
        String stripped = stripComments(conf);

        assertTrue(stripped.contains("listen 443 ssl") || stripped.contains("listen [::]:443 ssl"));
        assertTrue(stripped.contains("listen 80") || stripped.contains("listen [::]:80"));
        assertTrue(conf.contains("return 301 https://$host$request_uri"));
        assertTrue(stripped.contains("ssl_certificate"));
        assertTrue(stripped.contains("ssl_certificate_key"));
        assertTrue(stripped.contains("TLSv1.2") && stripped.contains("TLSv1.3"));
        assertTrue(stripped.contains("api.example.com"));
        assertTrue(stripped.contains("console.example.com"));

        assertTrue(stripped.contains("limit_req_zone") && stripped.contains("rate=20r/m"));
        assertTrue(stripped.contains("rate=30r/m"));
        assertTrue(stripped.contains("rate=5r/m"));
        assertTrue(stripped.contains("rate=120r/m"));
        assertTrue(conf.contains("location = /api/v1/end-user/auth/phone/request-code"));
        assertTrue(conf.contains("location ^~ /api/v1/end-user/auth/"));
        assertTrue(conf.contains("location ^~ /api/v1/auth/"));

        assertTrue(countActuatorBlocks(conf) >= 3, "actuator must be blocked on :80, API :443, and console :443");
        assertTrue(stripped.contains("server platform-app:8080"));
        assertFalse(mentionsManagementPort(stripped), "nginx recipe must not listen on or proxy to 8089/8090/8091");

        assertTrue(inc.contains("traceparent"));
        assertTrue(inc.contains("X-Correlation-Id") || inc.contains("X-Correlation-Id"));
        assertTrue(inc.contains("proxy_pass http://platform_app"));
        assertTrue(pemProblems("nginx/subjex.conf", conf).isEmpty());
        assertTrue(pemProblems("nginx/subjex-proxy.inc", inc).isEmpty());
        assertTrue(hostnameProblems("nginx/subjex.conf", conf).isEmpty());
    }

    @Test
    void apisixRecipeMatchesNginxResponsibilities() throws Exception {
        String yaml = Files.readString(edgeFile("apisix/apisix.yaml"));
        String notes = Files.readString(edgeFile("apisix/config-notes.yaml"));
        String stripped = stripComments(yaml);

        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        assertTrue(loaded instanceof java.util.Map, "apisix.yaml must be parseable YAML");

        assertTrue(stripped.contains("http_to_https"));
        assertTrue(stripped.contains("/actuator") || stripped.contains("/actuator*"));
        assertTrue(stripped.contains("request-termination"));
        assertTrue(stripped.contains("status_code: 404") || stripped.contains("status_code:404"));

        assertTrue(stripped.contains("limit-req"));
        assertTrue(stripped.contains("0.083"), "OTP 5/min ≈ 0.083/s");
        assertTrue(stripped.contains("0.5"), "end-user auth 30/min = 0.5/s");
        assertTrue(stripped.contains("0.333"), "operator auth 20/min ≈ 0.333/s");
        assertTrue(Pattern.compile("(?m)^\\s*rate:\\s*2\\s*$").matcher(stripped).find(), "general API 120/min = 2/s");

        assertTrue(stripped.contains("api.example.com"));
        assertTrue(stripped.contains("console.example.com"));
        assertTrue(stripped.contains("platform-app:8080"));
        assertFalse(mentionsManagementPort(stripped), "apisix recipe must not route to 8089/8090/8091");

        assertTrue(stripped.contains("PLACEHOLDER_API_CERT") && stripped.contains("PLACEHOLDER_API_KEY"));
        assertTrue(stripped.contains("PLACEHOLDER_CONSOLE_CERT") && stripped.contains("PLACEHOLDER_CONSOLE_KEY"));
        assertTrue(pemProblems("apisix/apisix.yaml", yaml).isEmpty());
        assertTrue(hostnameProblems("apisix/apisix.yaml", yaml).isEmpty());

        assertTrue(notes.contains("enable_admin: false") || notes.contains("enable_admin:false"));
        assertTrue(notes.contains("443") && notes.contains("80"));
        assertTrue(notes.contains("8089") || notes.contains("management"));
    }

    @Test
    void edgeTreeHasNoRealSecretsOrProductionHostnames() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(edgeDir())) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".crt")
                        || name.endsWith(".key")
                        || name.endsWith(".pem")
                        || name.endsWith(".p12")
                        || name.endsWith(".pfx")) {
                    problems.add(rel(file) + ": certificate or key file must not be committed");
                    return;
                }
                String text;
                try {
                    text = Files.readString(file);
                } catch (Exception e) {
                    problems.add(rel(file) + ": " + e.getMessage());
                    return;
                }
                String path = rel(file);
                if (path.endsWith(".conf")
                        || path.endsWith(".inc")
                        || path.endsWith(".yaml")
                        || path.endsWith(".yml")) {
                    problems.addAll(pemProblems(path, text));
                    problems.addAll(hostnameProblems(path, text));
                }
            });
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void readmeIsOperatorUsableAndHonest() throws Exception {
        String readme = Files.readString(edgeFile("README.md"));
        assertTrue(readme.contains("example.com"));
        assertTrue(readme.contains("PLACEHOLDER_") || readme.contains("PLACEHOLDER_*"));
        assertTrue(readme.contains("/actuator"));
        assertTrue(readme.contains("8089"));
        assertTrue(readme.contains("8090") && readme.contains("8091"));
        assertTrue(readme.toLowerCase(Locale.ROOT).contains("https"));
        assertTrue(readme.contains("301") || readme.toLowerCase(Locale.ROOT).contains("redirect"));
        assertTrue(readme.contains("entry-gateway"));
        assertTrue(readme.contains("20 / minute") || readme.contains("20r/m") || readme.contains("20 / min"));
        assertTrue(readme.contains("production cluster") && readme.contains("生产集群"));
        assertTrue(readme.contains("不是") && readme.contains("生产"));
        assertTrue(readme.contains("Never commit") || readme.contains("切勿把真实") || readme.contains("不要把真实"));
        assertTrue(readme.contains("ACME") || readme.contains("certbot"));
        assertTrue(readme.contains("How to replace certificates") || readme.contains("如何替换证书"));
        assertTrue(readme.contains("What this is not") || readme.contains("本配方不做"));
    }

    @Test
    void progressNoteRecordsEdgeTighteningWithoutLiveLanding() throws Exception {
        String note = Files.readString(repoRoot().resolve("docs/observability/wp9-observability-edge-progress.md"));
        assertTrue(note.contains("deploy/edge"));
        assertTrue(
                note.contains("Edge recipes [done]")
                        || note.contains("边缘配方 [done]")
                        || note.contains("WP9-edge [done]")
                        || note.contains("edge TLS")
                        || note.contains("边缘 TLS"));
        assertTrue(note.contains("不是生产集群") || note.toLowerCase(Locale.ROOT).contains("not a production cluster"));
        assertTrue(note.contains("EdgeRecipeHonestyTest") || note.contains("honesty"));
    }

    @Test
    void placeholderHelpersRejectRealKeysAndProductionHostnames() {
        assertTrue(pemProblems("ok", """
                -----BEGIN PRIVATE KEY-----
                PLACEHOLDER_API_KEY
                -----END PRIVATE KEY-----
                """).isEmpty());
        assertFalse(pemProblems("bad-key", """
                -----BEGIN PRIVATE KEY-----
                MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC0123456789ABCDEF
                -----END PRIVATE KEY-----
                """).isEmpty());
        assertTrue(hostnameProblems("ok-hosts", "server_name api.example.com console.example.com;").isEmpty());
        assertFalse(hostnameProblems("prod-host", "server_name api.subjex.example;").isEmpty());
        // documentation suffix is required; a made-up production-looking domain is rejected.
        // 必须是文档占位后缀；看起来像生产的自造域名会被拒绝。
        assertFalse(hostnameProblems("invented", "snis: [\"api.customer-prod.net\"]").isEmpty());
        assertTrue(mentionsManagementPort("server platform-app:8089;"));
        assertFalse(mentionsManagementPort("server platform-app:8080;"));
        assertTrue(mentionsManagementPort("proxy_pass http://platform-app:8090;"));
        assertFalse(mentionsManagementPort("server platform-app:8080;\n# never 8089/8090/8091\n"));
    }

    private static int countActuatorBlocks(String conf) {
        int count = 0;
        Matcher locations = Pattern.compile("location\\s+\\^~\\s+/actuator\\s*\\{([\\s\\S]*?)\\}").matcher(conf);
        while (locations.find()) {
            String body = locations.group(1);
            if (body.contains("deny all") || body.contains("return 404")) {
                count++;
            }
        }
        return count;
    }

    static List<String> pemProblems(String source, String text) {
        List<String> problems = new ArrayList<>();
        Matcher matcher = PEM_BLOCK.matcher(text);
        while (matcher.find()) {
            String kind = matcher.group(1);
            String body = matcher.group(2).replaceAll("\\s+", "");
            boolean placeholder = PLACEHOLDER.matcher(body).matches();
            boolean privateKey = kind.contains("PRIVATE KEY") || kind.contains("RSA PRIVATE") || kind.contains("EC PRIVATE");
            if (!placeholder) {
                problems.add(source + ": PEM block '" + kind + "' must be a PLACEHOLDER_* token, not real material");
            }
            if (privateKey && !placeholder) {
                problems.add(source + ": private key PEM is not a placeholder");
            }
        }
        return problems;
    }

    /**
     * Only inspect hostnames that the recipe actually binds (server_name, SNI, hosts)
     * or puts in a certificate path. Path fragments like {@code conf.d} and protocol
     * versions like {@code TLSv1.2} are not hostnames.
     * 只检查配方真正绑定的主机名（server_name、SNI、hosts）或证书路径里的名字。
     * {@code conf.d}、{@code TLSv1.2} 这类路径或协议片段不算主机名。
     */
    static List<String> hostnameProblems(String source, String text) {
        List<String> problems = new ArrayList<>();
        for (String raw : configuredHostnames(text)) {
            String host = stripCertExtension(raw).toLowerCase(Locale.ROOT);
            if (host.isBlank() || isIgnoredToken(host) || isAllowedHostname(host)) {
                continue;
            }
            problems.add(source + ": unexpected hostname '" + raw
                    + "' (use example.com / example.net / example.org placeholders)");
        }
        return problems;
    }

    static List<String> configuredHostnames(String text) {
        String stripped = stripComments(text);
        List<String> hosts = new ArrayList<>();
        Matcher serverName = Pattern.compile("(?i)server_name\\s+([^;]+);").matcher(stripped);
        while (serverName.find()) {
            for (String part : serverName.group(1).trim().split("\\s+")) {
                if (!part.isBlank() && !"_".equals(part)) {
                    hosts.add(part.trim());
                }
            }
        }
        Matcher keys = Pattern.compile("(?i)(?:snis|hosts|\\bhost)\\s*:\\s*(\\[[^\\]]*\\]|\"[^\"]+\"|\\S+)")
                .matcher(stripped);
        while (keys.find()) {
            Matcher quoted = Pattern.compile("\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9._-]+)")
                    .matcher(keys.group(1));
            while (quoted.find()) {
                String value = firstNonNull(quoted.group(1), quoted.group(2), quoted.group(3));
                if (value != null && value.contains(".")) {
                    hosts.add(value);
                }
            }
        }
        Matcher certs = Pattern.compile("(?i)ssl_certificate(?:_key)?\\s+(\\S+);").matcher(stripped);
        while (certs.find()) {
            String path = certs.group(1);
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (name.contains(".")) {
                hosts.add(name);
            }
        }
        return hosts;
    }

    private static String stripCertExtension(String raw) {
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        for (String ext : List.of(".crt", ".key", ".pem", ".p12", ".pfx", ".cer")) {
            if (name.toLowerCase(Locale.ROOT).endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static boolean mentionsManagementPort(String strippedOrRaw) {
        String stripped = stripComments(strippedOrRaw);
        return Pattern.compile("(?<!\\d)(:8089|:8090|:8091|listen\\s+8089|listen\\s+8090|listen\\s+8091)(?!\\d)")
                .matcher(stripped)
                .find();
    }

    private static boolean isAllowedHostname(String host) {
        for (String domain : DOC_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnoredToken(String token) {
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) {
            return true;
        }
        String last = token.substring(lastDot + 1);
        if (FILENAME_LABELS.contains(last)) {
            return true;
        }
        String[] labels = token.split("\\.");
        boolean allNumeric = true;
        for (String label : labels) {
            if (!label.chars().allMatch(Character::isDigit)) {
                allNumeric = false;
                break;
            }
        }
        return allNumeric;
    }

    static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
                // Keep YAML/nginx lines; drop from the first unquoted `#`. These recipes do not quote `#`.
                // 配方里没有带引号的 `#`，从首个 `#` 起到行尾都当作注释。
                out.append(line, 0, hash);
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static Path edgeFile(String relative) {
        Path file = edgeDir().resolve(relative);
        assertTrue(Files.isRegularFile(file), () -> "missing deploy/edge/" + relative);
        return file;
    }

    private static Path edgeDir() {
        Path dir = repoRoot().resolve("deploy/edge");
        assertTrue(Files.isDirectory(dir), "deploy/edge was not found");
        return dir;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("deploy/edge"))
                    && Files.isRegularFile(dir.resolve("deploy/k8s/platform-app.yaml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root was not found");
    }

    private static String rel(Path file) {
        return repoRoot().relativize(file).toString();
    }
}
