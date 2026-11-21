package com.subjex.platform.app.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * ProductionTenantAutoCreateDefaultTest — purpose/gate WP1-3: base and {@code prod}/{@code production}
 * default {@code platform.tenant.auto-create-on-submit=false}; {@code local} turns it on.
 * <p>
 * 目的/门禁 WP1-3：基础与生产 profile 默认关闭自动建户；{@code local} 打开本机逃生。
 */
class ProductionTenantAutoCreateDefaultTest {

    @Test
    void baseAndProductionProfilesDefaultAutoCreateOff() throws IOException {
        assertEquals("false", propertyWithProfiles());
        assertEquals("false", propertyWithProfiles("prod"));
        assertEquals("false", propertyWithProfiles("production"));
    }

    @Test
    void localProfileEnablesAutoCreateEscapeHatch() throws IOException {
        assertEquals("true", propertyWithProfiles("local"));
    }

    private static String propertyWithProfiles(String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        addYaml(environment, loader, "application.yml", false);
        for (String profile : profiles) {
            addYaml(environment, loader, "application-" + profile + ".yml", true);
        }
        String value = environment.getProperty("platform.tenant.auto-create-on-submit");
        assertTrue(value != null, "platform.tenant.auto-create-on-submit must be set in loaded yaml");
        return value;
    }

    private static void addYaml(
            StandardEnvironment environment, YamlPropertySourceLoader loader, String name, boolean override)
            throws IOException {
        Resource resource = new ClassPathResource(name);
        assertTrue(resource.exists(), () -> "missing classpath resource " + name);
        List<PropertySource<?>> sources = loader.load(name, resource);
        for (int i = sources.size() - 1; i >= 0; i--) {
            PropertySource<?> source = sources.get(i);
            if (override) {
                environment.getPropertySources().addFirst(source);
            } else {
                environment.getPropertySources().addLast(source);
            }
        }
    }
}
