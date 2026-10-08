package com.subjex.entity.declare;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * EntityCatalog — 实体目录：从 classpath 上每一份 {@code entities/*.entity.yaml} 读出校验过的实体，按 entityKey 索引。
 * <p>
 * Spring-free. Discovers file and JAR classpath roots. Fail-closed on bad YAML or duplicate keys.
 * 无 Spring。发现 file / JAR classpath 根。坏 YAML 或重复键一律拒绝。
 */
public final class EntityCatalog {

    private static final String ENTITIES_DIR = "entities";
    private static final String ENTITY_SUFFIX = ".entity.yaml";

    private final Map<String, RenderedEntity> byKey;

    /**
     * Build from an already-validated map — 由已校验的映射构造。
     */
    public EntityCatalog(Map<String, RenderedEntity> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    /**
     * Load every {@code entities/*.entity.yaml} on the given class loader — 加载给定 ClassLoader 上每一份实体声明。
     */
    public static EntityCatalog load(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new EntityDefinitionRejected("classLoader is missing");
        }
        EntityRenderer renderer = new EntityRenderer();
        ArrayList<RenderedEntity> entities = new java.util.ArrayList<>();
        for (String resourcePath : discoverEntityResources(classLoader)) {
            entities.add(renderer.render(readResource(classLoader, resourcePath)));
        }
        if (entities.isEmpty()) {
            throw new EntityDefinitionRejected("no entity definitions found on classpath");
        }
        return of(entities);
    }

    /**
     * Index already-rendered entities; refuse duplicate keys — 索引已渲染实体；重复键拒绝。
     */
    public static EntityCatalog of(Iterable<RenderedEntity> entities) {
        Map<String, RenderedEntity> loaded = new LinkedHashMap<>();
        for (RenderedEntity entity : entities) {
            if (entity == null) {
                throw new EntityDefinitionRejected("entity is missing");
            }
            RenderedEntity previous = loaded.put(entity.entityKey(), entity);
            if (previous != null) {
                throw new EntityDefinitionRejected("duplicate entityKey " + entity.entityKey());
            }
        }
        return new EntityCatalog(loaded);
    }

    /**
     * The entity with this key, or empty — 带这个键的实体，没有则为空。
     */
    public Optional<RenderedEntity> find(String entityKey) {
        return Optional.ofNullable(byKey.get(entityKey));
    }

    /**
     * Every known entity — 已知的每一份实体。
     */
    public Collection<RenderedEntity> all() {
        return byKey.values();
    }

    private static String readResource(ClassLoader classLoader, String resourcePath) {
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new EntityDefinitionRejected("entity resource missing: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Discover {@code entities/*.entity.yaml} under every classpath root — 在每个 classpath 根下发现实体声明。
     */
    static Collection<String> discoverEntityResources(ClassLoader classLoader) {
        // TreeMap keeps deterministic load order by relative path.
        Map<String, Boolean> paths = new TreeMap<>();
        Enumeration<URL> roots;
        try {
            roots = classLoader.getResources(ENTITIES_DIR);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                collectFromDirectory(url, paths);
            } else if ("jar".equals(protocol)) {
                collectFromJar(url, paths);
            }
        }
        return Collections.unmodifiableCollection(paths.keySet());
    }

    private static void collectFromDirectory(URL url, Map<String, Boolean> paths) {
        Path dir;
        try {
            dir = Path.of(url.toURI());
        } catch (URISyntaxException ex) {
            throw new EntityDefinitionRejected("bad entities directory URL");
        }
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + ENTITY_SUFFIX)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    paths.put(ENTITIES_DIR + "/" + file.getFileName(), Boolean.TRUE);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void collectFromJar(URL url, Map<String, Boolean> paths) {
        // jar:file:/path/to.jar!/entities
        String full = url.toString();
        int bang = full.indexOf("!/");
        if (bang < 0 || !full.startsWith("jar:")) {
            throw new EntityDefinitionRejected("bad jar entities URL");
        }
        String jarUri = full.substring("jar:".length(), bang);
        Path jarPath;
        try {
            jarPath = Path.of(URI.create(jarUri));
        } catch (IllegalArgumentException ex) {
            throw new EntityDefinitionRejected("bad jar file URI");
        }
        String prefix = ENTITIES_DIR + "/";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory()
                        && name.startsWith(prefix)
                        && name.endsWith(ENTITY_SUFFIX)
                        && name.indexOf('/', prefix.length()) < 0) {
                    paths.put(name, Boolean.TRUE);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
