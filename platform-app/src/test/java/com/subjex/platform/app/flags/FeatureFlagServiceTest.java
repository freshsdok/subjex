package com.subjex.platform.app.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.platform.contract.config.ConfigCenterPort;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOrigin;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FeatureFlagServiceTest — tenant → platform → default resolution. */
class FeatureFlagServiceTest {

    @Test
    void resolvesTenantOverPlatformOverDefault() {
        ConfigCenterPort config = mock(ConfigCenterPort.class);
        when(config.get(ConfigNamespaces.FLAGS, "acme/search.records"))
                .thenReturn(Optional.of(entry("acme/search.records", "false")));
        when(config.get(ConfigNamespaces.FLAGS, "search.records"))
                .thenReturn(Optional.of(entry("search.records", "true")));
        FeatureFlagService svc = new FeatureFlagService(config);

        assertFalse(svc.isEnabled("acme", FeatureFlagKeys.SEARCH_RECORDS, true));
        assertTrue(svc.isEnabled("other", FeatureFlagKeys.SEARCH_RECORDS, false)); // platform true
        when(config.get(ConfigNamespaces.FLAGS, "search.records")).thenReturn(Optional.empty());
        assertTrue(svc.isEnabled("other", FeatureFlagKeys.SEARCH_RECORDS, true)); // default
        assertFalse(svc.isEnabled(null, "missing.flag", false));
    }

    @Test
    void storageAndLogicalKeys() {
        assertEquals("acme/search.records", FeatureFlagService.storageKey("acme", "search.records"));
        assertEquals("search.records", FeatureFlagService.storageKey(null, "search.records"));
        assertEquals("search.records", FeatureFlagService.logicalKey("acme/search.records", "acme"));
        assertEquals("search.records", FeatureFlagService.logicalKey("search.records", null));
    }

    private static ConfigEntry entry(String key, String value) {
        return new ConfigEntry(ConfigNamespaces.FLAGS, key, value, ConfigOrigin.OVERRIDE, 1L);
    }
}
