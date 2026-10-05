package com.subjex.sample.consumer.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import org.junit.jupiter.api.Test;

class ConsumerSelfRegistrarTest {

    @Test
    void registersTheSampleConsumerEndpoint() throws Exception {
        ServiceRegistry registry = new InProcessServiceRegistry();
        ConsumerSelfRegistrar registrar = new ConsumerSelfRegistrar(registry, "127.0.0.1", 8081);

        registrar.run(null);

        ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.SAMPLE_CONSUMER).orElseThrow();
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8081, endpoint.port());
    }
}
