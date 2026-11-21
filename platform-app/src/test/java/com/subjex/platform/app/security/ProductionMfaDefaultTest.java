package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.env.PropertySource;

/**
 * ProductionMfaDefaultTest — purpose/gate WP1-2: {@code prod}/{@code production} default
 * {@code platform.mfa.required=true}; {@code local} and the base file keep it optional.
 * <p>
 * 目的/门禁 WP1-2：生产 profile 默认强制 MFA；{@code local} 与基础配置仍不强制。
 */
class ProductionMfaDefaultTest {

    @Test
    void prodAndProductionProfilesDefaultMfaRequired() throws IOException {
        assertEquals("true", propertyWithProfiles("prod"));
        assertEquals("true", propertyWithProfiles("production"));
    }

    @Test
    void localProfileKeepsMfaOptional() throws IOException {
        assertEquals("false", propertyWithProfiles("local"));
    }

    @Test
    void baseConfigurationWithoutProductionProfileKeepsMfaOptional() throws IOException {
        assertEquals("false", propertyWithProfiles());
    }

    /**
     * Load {@code application.yml} then each {@code application-{profile}.yml}, profile last so it wins.
     * 先加载基础配置，再加载各 profile 文件，后者覆盖前者。
     */
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
        String value = environment.getProperty("platform.mfa.required");
        assertTrue(value != null, "platform.mfa.required must be set in loaded yaml");
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
