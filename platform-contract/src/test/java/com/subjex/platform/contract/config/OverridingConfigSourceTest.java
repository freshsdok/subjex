package com.subjex.platform.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverridingConfigSourceTest {

    @Test
    void memorySourceOverridesOneNamedKey() {
        String hostKey = StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP);
        String portKey = StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP);
        ConfigSource local = new LocalApplicationConfig(Map.of(hostKey, "127.0.0.1", portKey, "8080")::get);
        MemoryConfigOverride memory = new MemoryConfigOverride();
        memory.override(portKey, "9090");
        ConfigSource layered = new OverridingConfigSource(memory, local);

        assertEquals("9090", layered.lookup(portKey).orElseThrow());
        assertEquals("127.0.0.1", layered.lookup(hostKey).orElseThrow());
        assertTrue(layered.lookup("platform.absent").isEmpty());
    }

    @Test
    void listingNamesTheWinningLayerInPlainWords() {
        String hostKey = StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP);
        String portKey = StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP);
        ConfigSource local = new LocalApplicationConfig(Map.of(hostKey, "127.0.0.1", portKey, "8080")::get);
        MemoryConfigOverride memory = new MemoryConfigOverride();
        memory.override(portKey, "9090");
        ConfigListing listing = new ConfigListing(memory, local);

        ConfigEntry host = listing.entry(hostKey).orElseThrow();
        ConfigEntry port = listing.entry(portKey).orElseThrow();
        assertEquals(ConfigOrigin.LOCAL, host.origin());
        assertEquals("本地文件", host.origin().chinese());
        assertEquals("local file", host.origin().english());
        assertEquals(ConfigOrigin.OVERRIDE, port.origin());
        assertEquals("内存覆盖", port.origin().chinese());
        assertEquals("memory override", port.origin().english());

        List<ConfigEntry> rows = listing.rows(List.of(hostKey, portKey));
        assertEquals(2, rows.size());
        assertEquals("9090", rows.stream().filter(row -> row.key().equals(portKey)).findFirst().orElseThrow().value());
    }
}
