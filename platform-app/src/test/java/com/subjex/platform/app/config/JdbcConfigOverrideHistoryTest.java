package com.subjex.platform.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.subjex.platform.app.jdbc.JdbcConfigOverride;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOverrideHistoryEntry;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcConfigOverrideHistoryTest — Config-6a history append + Config-7a restore put (ADR 0012).
 * Gates: puts append history; restore from old revision writes a new revision (no rewind).
 * <p>
 * 目的/门禁：put 追加历史；从旧修订恢复会写入新修订（不倒带）。
 */
class JdbcConfigOverrideHistoryTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void putAppendsHistoryOldestFirst(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcConfigOverride store = new JdbcConfigOverride(jdbc, Clock.systemUTC());

        assertEquals(1L, store.put(ConfigNamespaces.DEFAULT, "platform.demo.message", "one"));
        assertEquals(2L, store.put(ConfigNamespaces.DEFAULT, "platform.demo.message", "two"));

        List<ConfigOverrideHistoryEntry> rows =
                store.history(ConfigNamespaces.DEFAULT, "platform.demo.message");
        assertEquals(2, rows.size());
        assertEquals(1L, rows.get(0).revision());
        assertEquals("one", rows.get(0).value());
        assertEquals(2L, rows.get(1).revision());
        assertEquals("two", rows.get(1).value());
        assertEquals("two", store.lookup(ConfigNamespaces.DEFAULT, "platform.demo.message").orElseThrow());

        // Other namespace stays empty — 其他命名空间仍空。
        assertEquals(List.of(), store.history("tenant-north", "platform.demo.message"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void restorePutsNewRevisionFromHistory(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcConfigOverride store = new JdbcConfigOverride(jdbc, Clock.systemUTC());
        ConfigCatalog catalog = new ConfigCatalog(
                new ConfigListing(store, new LocalApplicationConfig(Map.<String, String>of()::get)), store);

        catalog.put(ConfigNamespaces.DEFAULT, "k", "one");
        catalog.put(ConfigNamespaces.DEFAULT, "k", "two");
        ConfigEntry restored = catalog.restore(ConfigNamespaces.DEFAULT, "k", 1L);
        assertEquals(3L, restored.revision());
        assertEquals("one", restored.value());
        assertEquals("one", catalog.get(ConfigNamespaces.DEFAULT, "k").orElseThrow().value());

        List<ConfigOverrideHistoryEntry> rows = store.history(ConfigNamespaces.DEFAULT, "k");
        assertEquals(3, rows.size());
        assertEquals("one", rows.get(0).value());
        assertEquals("two", rows.get(1).value());
        assertEquals("one", rows.get(2).value());
        assertEquals(3L, rows.get(2).revision());
    }
}
