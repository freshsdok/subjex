package com.subjex.platform.app.storage;

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
 * ProductionStorageDefaultTest — purpose/gate WP1-4: {@code prod}/{@code production} default
 * {@code platform.storage.type=s3}; base and {@code local} stay {@code local}.
 * <p>
 * 目的/门禁 WP1-4：生产 profile 默认 s3；基础与 {@code local} 仍为本地目录。
 */
class ProductionStorageDefaultTest {

    @Test
    void baseAndLocalProfilesDefaultToDirectoryStorage() throws IOException {
        assertEquals("local", propertyWithProfiles());
        assertEquals("local", propertyWithProfiles("local"));
    }

    @Test
    void productionProfilesDefaultToS3() throws IOException {
        assertEquals("s3", propertyWithProfiles("prod"));
        assertEquals("s3", propertyWithProfiles("production"));
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
        String value = environment.getProperty("platform.storage.type");
        assertTrue(value != null, "platform.storage.type must be set in loaded yaml");
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
