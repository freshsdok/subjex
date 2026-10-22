package com.subjex.platform.app.config;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.config.ConfigETags;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOverrideHistoryEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ConfigApiEndpoint — 配置 JSON API：列表 / PUT / 历史 GET（6b）/ 从历史恢复 POST（7a）。
 * Config JSON API: list, PUT, history GET (6b), restore POST from history (7a).
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
     * GET override history for one key (Config-6b). Empty list when never overridden.
     * 读取某一键的覆盖历史（Config-6b）；从未覆盖时返回空列表。
     */
    @GetMapping(PATH + "/{key}/history")
    public ConfigHistoryDocument history(
            @PathVariable("key") String key,
            @RequestParam(value = "namespace", required = false) String namespace) {
        String ns = namespace == null || namespace.isBlank() ? ConfigNamespaces.DEFAULT : namespace.trim();
        List<ConfigHistoryEntryDocument> entries = catalog.history(ns, key).stream()
                .map(ConfigApiEndpoint::historyEntry)
                .toList();
        return new ConfigHistoryDocument(ns, key, entries);
    }


    /**
     * POST restore: explicit put of a history row's value (Config-7a). Never rewinds history.
     * Optional If-Match on the <em>current</em> override revision (412 on mismatch).
     * 从历史行取值再 put（Config-7a）；不倒带历史。可选 If-Match 对应当前覆盖修订。
     */
    @PostMapping(PATH + "/{key}/history/{revision}/restore")
    public ResponseEntity<ConfigEntryDocument> restore(
            @PathVariable("key") String key,
            @PathVariable("revision") long revision,
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestHeader(value = ConfigETags.HEADER_IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal OperatorPrincipal operator) {
        String ns = namespace == null || namespace.isBlank() ? ConfigNamespaces.DEFAULT : namespace.trim();
        long current = catalog.get(ns, key).map(ConfigEntry::revision).orElse(0L);
        if (!ConfigETags.matchAllows(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "config revision mismatch");
        }
        ConfigEntry written;
        try {
            written = catalog.restore(ns, key, revision);
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage());
        }
        String auditTarget = (ConfigNamespaces.DEFAULT.equals(ns) ? key : ns + "/" + key) + "@" + revision;
        audit.record(operator, OperatorActionAudit.CONFIG_RESTORE, auditTarget, AuditOutcome.ALLOWED);
        String etag = ConfigETags.ofRevision(written.revision());
        return ResponseEntity.ok()
                .eTag(stripQuotes(etag))
                .body(entry(written));
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


    private static ConfigHistoryEntryDocument historyEntry(ConfigOverrideHistoryEntry row) {
        return new ConfigHistoryEntryDocument(
                row.namespace(), row.key(), row.value(), row.revision(), row.overriddenAt());
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

    /** History document for one key — 某一键的历史文档。 */
    public record ConfigHistoryDocument(String namespace, String key, List<ConfigHistoryEntryDocument> entries) {}

    /** One history row — 一行历史。 */
    public record ConfigHistoryEntryDocument(
            String namespace, String key, String value, long revision, Instant overriddenAt) {}
}
