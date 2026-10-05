package com.subjex.platform.app.config;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.config.ConfigEntry;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ConfigApiEndpoint — 配置接口：{@code /config} 页面的 JSON 孪生，列出关注的键，并能压过一个键。
 * <p>
 * {@code origin} says which layer the effective value came from. An override goes through the same
 * {@link ConfigCatalog} as {@link ConfigEntriesEndpoint} and leaves the same audit entry
 * ({@code config.override}, target = key). A blank value is refused with 400.
 * {@code origin} 说明生效值来自哪一层。覆盖与 {@link ConfigEntriesEndpoint} 走同一个 {@link ConfigCatalog}，
 * 留下同样的审计（{@code config.override}，对象是键）。空白值以 400 拒绝。
 */
@RestController
public class ConfigApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/config";

    private final ConfigCatalog catalog;
    private final OperatorActionAudit audit;

    public ConfigApiEndpoint(ConfigCatalog catalog, OperatorActionAudit audit) {
        this.catalog = catalog;
        this.audit = audit;
    }

    @GetMapping(PATH)
    public ConfigDocument config() {
        List<ConfigEntryDocument> entries = catalog.list().stream()
                .map(ConfigApiEndpoint::entry)
                .toList();
        return new ConfigDocument(entries);
    }

    @PutMapping(PATH + "/{key}")
    public ConfigEntryDocument override(
            @PathVariable("key") String key,
            @RequestBody(required = false) ConfigValueDocument document,
            @AuthenticationPrincipal OperatorPrincipal operator) {
        if (document == null || document.value() == null || document.value().isBlank()) {
            throw new IllegalArgumentException("config value must not be blank");
        }
        catalog.override(key, document.value());
        audit.record(operator, OperatorActionAudit.CONFIG_OVERRIDE, key, AuditOutcome.ALLOWED);
        return catalog.entry(key)
                .map(ConfigApiEndpoint::entry)
                .orElseThrow(() -> new IllegalStateException("config override for " + key + " is not visible"));
    }

    private static ConfigEntryDocument entry(ConfigEntry found) {
        return new ConfigEntryDocument(found.key(), found.value(), found.origin().apiWord());
    }

    /**
     * ConfigDocument — 配置文档：关注的键及其生效值。
     */
    public record ConfigDocument(List<ConfigEntryDocument> entries) {}

    /**
     * ConfigEntryDocument — 一条生效配置：键、值、来源层（origin）。
     */
    public record ConfigEntryDocument(String key, String value, String origin) {}

    /**
     * ConfigValueDocument — 覆盖请求体：新值。
     */
    public record ConfigValueDocument(String value) {}
}
