package com.subjex.platform.app.config;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.config.ConfigETags;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigNamespaces;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ConfigApiEndpoint — 配置 JSON API：列表 + PUT；PUT 支持 If-Match → 412（Config-5c）。
 */
@RestController
public class ConfigApiEndpoint {

    public static final String PATH = JsonApi.BASE + "/config";

    private final ConfigCatalog catalog;
    private final OperatorActionAudit audit;

    public ConfigApiEndpoint(ConfigCatalog catalog, OperatorActionAudit audit) {
        this.catalog = catalog;
        this.audit = audit;
    }

    /** List config entries for namespace — 按命名空间列出配置条目。 */
    @GetMapping(PATH)
    public ConfigDocument config(
            @RequestParam(value = "namespace", required = false) String namespace) {
        String ns = namespace == null || namespace.isBlank() ? ConfigNamespaces.DEFAULT : namespace.trim();
        List<ConfigEntryDocument> entries = catalog.list(ns).stream()
                .map(ConfigApiEndpoint::entry)
                .toList();
        return new ConfigDocument(ns, entries);
    }

    /**
     * PUT override with optional If-Match (412 on mismatch) - Config-5c fail-closed concurrency -
     * 覆盖写入；可选 If-Match（不匹配 412）— Config-5c 并发失败关闭。
     */
    @PutMapping(PATH + "/{key}")
    public ResponseEntity<ConfigEntryDocument> override(
            @PathVariable("key") String key,
            @RequestParam(value = "namespace", required = false) String namespaceQuery,
            @RequestHeader(value = ConfigETags.HEADER_IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) ConfigValueDocument document,
            @AuthenticationPrincipal OperatorPrincipal operator) {
        if (document == null || document.value() == null || document.value().isBlank()) {
            throw new IllegalArgumentException("config value must not be blank");
        }
        String ns = resolveNamespace(namespaceQuery, document.namespace());
        long current = catalog.get(ns, key).map(ConfigEntry::revision).orElse(0L);
        if (!ConfigETags.matchAllows(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "config revision mismatch");
        }
        ConfigEntry written = catalog.put(ns, key, document.value());
        String auditTarget = ConfigNamespaces.DEFAULT.equals(ns) ? key : ns + "/" + key;
        audit.record(operator, OperatorActionAudit.CONFIG_OVERRIDE, auditTarget, AuditOutcome.ALLOWED);
        String etag = ConfigETags.ofRevision(written.revision());
        return ResponseEntity.ok()
                .eTag(stripQuotes(etag))
                .body(entry(written));
    }

    private static String resolveNamespace(String query, String body) {
        if (body != null && !body.isBlank()) {
            return body.trim();
        }
        if (query != null && !query.isBlank()) {
            return query.trim();
        }
        return ConfigNamespaces.DEFAULT;
    }

    private static String stripQuotes(String quoted) {
        if (quoted != null && quoted.length() >= 2 && quoted.charAt(0) == '"') {
            return quoted.substring(1, quoted.length() - 1);
        }
        return quoted;
    }

    private static ConfigEntryDocument entry(ConfigEntry found) {
        return new ConfigEntryDocument(
                found.namespace(), found.key(), found.value(), found.origin().apiWord(), found.revision());
    }

    public record ConfigDocument(String namespace, List<ConfigEntryDocument> entries) {
        public ConfigDocument(List<ConfigEntryDocument> entries) {
            this(ConfigNamespaces.DEFAULT, entries);
        }
    }

    public record ConfigEntryDocument(String namespace, String key, String value, String origin, long revision) {
        public ConfigEntryDocument(String key, String value, String origin) {
            this(ConfigNamespaces.DEFAULT, key, value, origin, 0L);
        }
    }

    public record ConfigValueDocument(String value, String namespace) {
        public ConfigValueDocument(String value) {
            this(value, null);
        }
    }
}
