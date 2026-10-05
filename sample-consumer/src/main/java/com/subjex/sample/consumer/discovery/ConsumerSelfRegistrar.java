package com.subjex.sample.consumer.discovery;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import java.util.function.IntSupplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * ConsumerSelfRegistrar — 消费者自登记：第二个进程把自己的端点登记到 platform-app。
 * <p>
 * The call is HTTP. The port is the one this process actually bound, not a placeholder of zero.
 * When platform-app cannot be reached, registration is skipped and static config remains the fallback.
 * 这次调用走 HTTP。端口是本进程真正绑上的端口，不是占位的零。
 * platform-app 连不上时跳过登记，静态配置仍是兜底。
 */
public final class ConsumerSelfRegistrar implements ApplicationRunner {

    private final ServiceRegistry registry;
    private final String host;
    private final IntSupplier port;

    public ConsumerSelfRegistrar(ServiceRegistry registry, String host, int port) {
        this(registry, host, () -> port);
    }

    public ConsumerSelfRegistrar(ServiceRegistry registry, String host, IntSupplier port) {
        this.registry = registry;
        this.host = host;
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        registry.register(new ServiceEndpoint(PlatformServiceNames.SAMPLE_CONSUMER, host, port.getAsInt()));
    }
}
