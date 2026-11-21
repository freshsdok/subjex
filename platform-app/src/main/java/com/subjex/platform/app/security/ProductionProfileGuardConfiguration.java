package com.subjex.platform.app.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * ProductionProfileGuardConfiguration — 启动时执行生产 profile 门禁，先于本地种子写入。
 * <p>
 * Always loaded. The constructor runs during context refresh, before {@code ApplicationRunner}
 * beans such as {@link LocalOperatorConfiguration} seed a local operator. Prefer fail-fast over
 * skipping the seeder when {@code local} and {@code prod}/{@code production} are both active.
 * 始终装配。构造在容器刷新时执行，早于 {@link LocalOperatorConfiguration} 等 {@code ApplicationRunner}
 * 写入本地操作员。当 {@code local} 与 {@code prod}/{@code production} 同时激活时，宁可启动失败，也不跳过种子。
 */
@Configuration
public class ProductionProfileGuardConfiguration {

    public ProductionProfileGuardConfiguration(Environment environment) {
        ProductionProfileGuard.assertCompatible(environment.getActiveProfiles());
    }
}
