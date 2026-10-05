package com.subjex.platform.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
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
}
