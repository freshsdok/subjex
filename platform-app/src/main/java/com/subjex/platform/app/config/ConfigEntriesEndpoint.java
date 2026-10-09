package com.subjex.platform.app.config;

import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.config.ConfigETags;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.HttpConfigSource;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ConfigEntriesEndpoint — 跨进程配置条目：ETag / If-None-Match（304）/ If-Match（412）（Config-5c）。
 * <p>
 * POST stays {@code 204} when unconditional or If-Match succeeds.
 * POST 在无条件或 If-Match 成功时仍返回 204。
 */
@RestController
@RequestMapping(HttpConfigSource.ENTRIES_PATH)
public class ConfigEntriesEndpoint {

    private final ConfigCatalog catalog;
    private final OperatorActionAudit audit;

    public ConfigEntriesEndpoint(ConfigCatalog catalog, OperatorActionAudit audit) {
        this.catalog = catalog;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<EntryDocument> read(
            @RequestParam("key") String key,
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestHeader(value = ConfigETags.HEADER_IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String ns = namespace == null || namespace.isBlank() ? ConfigNamespaces.DEFAULT : namespace.trim();
        Optional<ConfigEntry> entry = catalog.get(ns, key);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ConfigEntry found = entry.get();
        String etag = ConfigETags.ofRevision(found.revision());
        if (ConfigETags.noneMatchHits(ifNoneMatch, found.revision())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(stripQuotes(etag)).build();
        }
        return ResponseEntity.ok()
                .eTag(stripQuotes(etag))
                .body(new EntryDocument(
                        found.namespace(),
                        found.key(),
                        found.value(),
                        found.origin().apiWord(),
                        found.revision()));
    }

    @PostMapping
    public ResponseEntity<Void> override(
            @RequestBody OverrideDocument document,
            @RequestHeader(value = ConfigETags.HEADER_IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal OperatorPrincipal operator) {
        if (document == null) {
            throw new IllegalArgumentException("config override is missing");
        }
        String ns = document.namespace() == null || document.namespace().isBlank()
                ? ConfigNamespaces.DEFAULT
                : document.namespace().trim();
        long current = catalog.get(ns, document.key()).map(ConfigEntry::revision).orElse(0L);
        if (!ConfigETags.matchAllows(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "config revision mismatch");
        }
        ConfigEntry written = catalog.put(ns, document.key(), document.value());
        String auditTarget = ConfigNamespaces.DEFAULT.equals(ns) ? document.key() : ns + "/" + document.key();
        audit.record(operator, OperatorActionAudit.CONFIG_OVERRIDE, auditTarget, AuditOutcome.ALLOWED);
        return ResponseEntity.noContent().eTag(stripQuotes(ConfigETags.ofRevision(written.revision()))).build();
    }

    /** Spring {@code eTag()} wants the value without surrounding quotes. */
    private static String stripQuotes(String quoted) {
        if (quoted != null && quoted.length() >= 2 && quoted.charAt(0) == '"') {
            return quoted.substring(1, quoted.length() - 1);
        }
        return quoted;
    }

    public record EntryDocument(String namespace, String key, String value, String source, long revision) {
        public EntryDocument(String key, String value, String source) {
            this(ConfigNamespaces.DEFAULT, key, value, source, 0L);
        }
    }

    public record OverrideDocument(String key, String value, String namespace) {
        public OverrideDocument(String key, String value) {
            this(key, value, null);
        }
    }
}
