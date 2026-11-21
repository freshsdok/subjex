package com.subjex.platform.app.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * WorkflowCatalog — 工作流目录：从 classpath 上每一份 {@code workflows/*.workflow.yaml} 读出校验过的薄状态机，按 definitionKey 索引。
 * <p>
 * Same scanning style as {@code EntityCatalog} / {@code FormCatalog} / {@code PageCatalog}. A jar
 * may add a machine without changing platform kernel source. This is not a BPM designer.
 * 扫描方式与实体/表单/页面目录相同。域模块可在不改内核源码的情况下加入机器。这不是 BPM 设计器。
 */
@Component
public class WorkflowCatalog {

    /** Classpath pattern for every thin workflow declaration — 每一份薄工作流声明的 classpath 模式。 */
    static final String WORKFLOW_PATTERN = "classpath*:workflows/*.workflow.yaml";

    private final Map<String, WorkflowDefinition> byKey;
    private final Map<String, WorkflowDefinition> byEntityKey;

    public WorkflowCatalog() {
        this(loadAll());
    }

    WorkflowCatalog(Map<String, WorkflowDefinition> byKey) {
        this.byKey = Map.copyOf(byKey);
        this.byEntityKey = indexByEntityKey(this.byKey);
    }

    /**
     * Every known machine, ordered by definitionKey — 已知的每一台机器，按 definitionKey 排序。
     */
    public List<WorkflowDefinition> list() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(WorkflowDefinition::definitionKey))
                .toList();
    }

    /**
     * The machine with this key, or empty — 带这个键的机器，没有则为空。
     */
    public Optional<WorkflowDefinition> find(String definitionKey) {
        if (definitionKey == null || definitionKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(definitionKey.trim()));
    }

    /**
     * The machine bound to this entity key, or empty — 绑定到该实体键的机器，没有则为空。
     */
    public Optional<WorkflowDefinition> findByEntityKey(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byEntityKey.get(entityKey.trim()));
    }

    /**
     * Definition that auto-starts after an upsert of this entity, or empty.
     * 该实体 upsert 后应自动启动的定义；未声明则为空。
     */
    public Optional<WorkflowDefinition> findAutoStart(String entityKey) {
        return findByEntityKey(entityKey).filter(WorkflowDefinition::autoStartOnUpsert);
    }

    /**
     * The machine with this key, or refuse — 带这个键的机器；没有则拒绝。
     */
    public WorkflowDefinition require(String definitionKey) {
        return find(definitionKey)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow definition: " + definitionKey));
    }

    private static Map<String, WorkflowDefinition> loadAll() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(WORKFLOW_PATTERN);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        WorkflowDeclarationReader reader = new WorkflowDeclarationReader();
        Map<String, WorkflowDefinition> loaded = new LinkedHashMap<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            WorkflowDefinition definition = reader.render(read(resource));
            WorkflowDefinition previous = loaded.put(definition.definitionKey(), definition);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate definitionKey on classpath: " + definition.definitionKey());
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("no workflow declarations found at " + WORKFLOW_PATTERN);
        }
        return Map.copyOf(loaded);
    }

    private static Map<String, WorkflowDefinition> indexByEntityKey(Map<String, WorkflowDefinition> byKey) {
        Map<String, WorkflowDefinition> byEntity = new LinkedHashMap<>();
        for (WorkflowDefinition definition : byKey.values()) {
            String entityKey = definition.entityKey();
            if (entityKey == null || entityKey.isBlank()) {
                continue;
            }
            WorkflowDefinition previous = byEntity.put(entityKey, definition);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate workflow entityKey: "
                                + entityKey
                                + " ("
                                + previous.definitionKey()
                                + " and "
                                + definition.definitionKey()
                                + ")");
            }
        }
        return Map.copyOf(byEntity);
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
