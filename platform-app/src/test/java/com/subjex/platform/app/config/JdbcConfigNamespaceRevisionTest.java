package com.subjex.platform.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.jdbc.JdbcConfigOverride;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOrigin;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;


/**
 * JdbcConfigNamespaceRevisionTest — purpose/gate Config-5b: namespaced overrides bump revision.
 * Gates: put increments revision; list/get see override over base (dual H2 MODE).
 * <p>
 * 目的/门禁 Config-5b：命名空间覆盖递增修订号；列表/读取覆盖压过底层。
 */
class JdbcConfigNamespaceRevisionTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void namespacesAreIsolatedAndPutBumpsRevision(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcConfigOverride store = new JdbcConfigOverride(jdbc, Clock.systemUTC());
        ConfigListing listing = new ConfigListing(
                store, new LocalApplicationConfig(Map.of("platform.demo.message", "local")::get));
        ConfigCatalog catalog = new ConfigCatalog(listing, store);

        ConfigEntry first = catalog.put(ConfigNamespaces.DEFAULT, "platform.demo.message", "one");
        assertEquals(1L, first.revision());
        assertEquals(ConfigNamespaces.DEFAULT, first.namespace());

        ConfigEntry second = catalog.put(ConfigNamespaces.DEFAULT, "platform.demo.message", "two");
        assertEquals(2L, second.revision());
        assertEquals("two", catalog.get("platform.demo.message").orElseThrow().value());

        ConfigEntry otherNs = catalog.put("tenant-north", "platform.demo.message", "north");
        assertEquals(1L, otherNs.revision());
        assertEquals("tenant-north", otherNs.namespace());

        assertEquals("two", catalog.get(ConfigNamespaces.DEFAULT, "platform.demo.message").orElseThrow().value());
        assertEquals(2L, catalog.get(ConfigNamespaces.DEFAULT, "platform.demo.message").orElseThrow().revision());
        assertEquals("north", catalog.get("tenant-north", "platform.demo.message").orElseThrow().value());
        assertTrue(catalog.get("tenant-other", "platform.demo.message").isEmpty());

        assertFalse(catalog.list("tenant-north").stream()
                .anyMatch(row -> row.key().equals("platform.discovery.static.platform-app.host")),
                "non-default namespace is override-only (no watched local keys)");
        assertTrue(catalog.list(ConfigNamespaces.DEFAULT).stream()
                .anyMatch(row -> row.key().equals("platform.demo.message") && row.revision() == 2L));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void flatOverrideStillUsesDefaultNamespace(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcConfigOverride store = new JdbcConfigOverride(jdbc, Clock.systemUTC());
        store.override("k", "v");
        assertEquals(Optional.of("v"), store.lookup("k"));
        assertEquals(Optional.of(1L), store.revision(ConfigNamespaces.DEFAULT, "k"));
        assertEquals(Optional.empty(), store.lookup("other", "k"));
    }
}
