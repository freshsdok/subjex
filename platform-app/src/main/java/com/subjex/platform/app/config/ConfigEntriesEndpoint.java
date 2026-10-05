package com.subjex.platform.app.config;

import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.HttpConfigSource;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ConfigEntriesEndpoint — 配置条目接口：别的进程在这里读生效值，或压过一个具名键。
 * <p>
 * This is not a separate configuration server. The human page is {@code GET /config}, not this JSON entry.
 * 这不是单独的配置服务器。给人看的页面是 {@code GET /config}，不是这份 JSON 条目。
 */
@RestController
@RequestMapping(HttpConfigSource.ENTRIES_PATH)
public class ConfigEntriesEndpoint {

    private final ConfigCatalog catalog;

    public ConfigEntriesEndpoint(ConfigCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<EntryDocument> read(@RequestParam("key") String key) {
        Optional<ConfigEntry> entry = catalog.entry(key);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ConfigEntry found = entry.get();
        return ResponseEntity.ok(new EntryDocument(found.key(), found.value(), found.origin().apiWord()));
    }

    @PostMapping
    public ResponseEntity<Void> override(@RequestBody OverrideDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("config override is missing");
        }
        catalog.override(document.key(), document.value());
        return ResponseEntity.noContent().build();
    }

    /**
     * One effective entry on the wire — 线上的一条生效配置。
     */
    public record EntryDocument(String key, String value, String source) {}

    /**
     * One named override on the wire — 线上的一次具名覆盖。
     */
    public record OverrideDocument(String key, String value) {}
}
