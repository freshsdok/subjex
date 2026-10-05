package com.subjex.platform.app.page;

import com.subjex.page.declare.PageRenderer;
import com.subjex.page.declare.RenderedFlow;
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
 * PageCatalog — 页面目录：从 classpath 上每一份 {@code *.flow.yaml} 读出校验过的流程，按 flowKey 索引。
 * <p>
 * Every declarative flow shipped by {@code page-declare} is loaded once. This is not a designer.
 * {@code page-declare} 带上的每一份声明式流程只加载一次。这不是设计器。
 */
@Component
public class PageCatalog {

    /** Classpath pattern for every declarative flow — 每一份声明式流程的 classpath 模式。 */
    static final String FLOW_PATTERN = "classpath*:flows/*.flow.yaml";

    private final Map<String, RenderedFlow> byKey;

    public PageCatalog() {
        this.byKey = loadAll();
    }

    PageCatalog(Map<String, RenderedFlow> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    /**
     * Every known flow, ordered by flowKey — 已知的每一份流程，按 flowKey 排序。
     */
    public List<RenderedFlow> list() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(RenderedFlow::flowKey))
                .toList();
    }

    /**
     * The flow with this key, or empty — 带这个键的流程，没有则为空。
     */
    public Optional<RenderedFlow> find(String flowKey) {
        return Optional.ofNullable(byKey.get(flowKey));
    }

    /**
     * The flow with this key, or refuse — 带这个键的流程；没有则拒绝。
     */
    public RenderedFlow require(String flowKey) {
        RenderedFlow flow = byKey.get(flowKey);
        if (flow == null) {
            throw new IllegalArgumentException("unknown flow: " + flowKey);
        }
        return flow;
    }

    private static Map<String, RenderedFlow> loadAll() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(FLOW_PATTERN);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        PageRenderer renderer = new PageRenderer();
        Map<String, RenderedFlow> loaded = new LinkedHashMap<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            String yaml = read(resource);
            RenderedFlow flow = renderer.render(yaml);
            RenderedFlow previous = loaded.put(flow.flowKey(), flow);
            if (previous != null) {
                throw new IllegalStateException("duplicate flowKey on classpath: " + flow.flowKey());
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("no flows found at " + FLOW_PATTERN);
        }
        return Map.copyOf(loaded);
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
