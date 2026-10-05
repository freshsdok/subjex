package com.subjex.platform.app.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import org.junit.jupiter.api.Test;

class PlatformSelfRegistrarTest {

    @Test
    void registersThePlatformAppEndpoint() throws Exception {
        ServiceRegistry registry = new InProcessServiceRegistry();
        PlatformSelfRegistrar registrar = new PlatformSelfRegistrar(registry, "127.0.0.1", 8080);

        registrar.run(null);

        ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8080, endpoint.port());
    }
}
