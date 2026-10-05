package com.subjex.platform.app.form;

import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
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
 * FormCatalog — 表单目录：从 classpath 上每一份 {@code *.form.yaml} 读出校验过的字段列表，按 formKey 索引。
 * <p>
 * Every declarative form shipped by {@code form-render} (and any other jar on the classpath) is loaded once.
 * {@link #publication()} still resolves {@code endpoint-publication} for codegen and HTML pages that need that form.
 * This is not a designer and not an online form library.
 * {@code form-render}（以及 classpath 上其它 jar）带上的每一份声明式表单只加载一次。
 * {@link #publication()} 仍解析 {@code endpoint-publication}，给仍需要该表单的代码生成与 HTML 页用。
 * 这不是设计器，也不是在线表单库。
 */
@Component
public class FormCatalog {

    /** Classpath pattern for every declarative form — 每一份声明式表单的 classpath 模式。 */
    static final String FORM_PATTERN = "classpath*:forms/*.form.yaml";

    /** Publication form key kept for codegen/HTML — 代码生成与 HTML 页仍用的发布表单键。 */
    static final String PUBLICATION_KEY = "endpoint-publication";

    /** Classpath path of the publication YAML (tests) — 发布表单 YAML 的 classpath 路径（测试用）。 */
    static final String PUBLICATION_RESOURCE = "/forms/endpoint-publication.form.yaml";

    private final Map<String, RenderedForm> byKey;

    public FormCatalog() {
        this.byKey = loadAll();
    }

    FormCatalog(Map<String, RenderedForm> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    /**
     * The endpoint-publication form — 端点发布表单。
     */
    public RenderedForm publication() {
        return require(PUBLICATION_KEY);
    }

    /**
     * Every known form, ordered by formKey — 已知的每一份表单，按 formKey 排序。
     */
    public List<RenderedForm> list() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(RenderedForm::formKey))
                .toList();
    }

    /**
     * The form with this key, or empty — 带这个键的表单，没有则为空。
     */
    public Optional<RenderedForm> find(String formKey) {
        return Optional.ofNullable(byKey.get(formKey));
    }

    /**
     * The form with this key, or refuse — 带这个键的表单；没有则拒绝。
     */
    public RenderedForm require(String formKey) {
        RenderedForm form = byKey.get(formKey);
        if (form == null) {
            throw new IllegalArgumentException("unknown form: " + formKey);
        }
        return form;
    }

    private static Map<String, RenderedForm> loadAll() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(FORM_PATTERN);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        FormRenderer renderer = new FormRenderer();
        Map<String, RenderedForm> loaded = new LinkedHashMap<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            String yaml = read(resource);
            RenderedForm form = renderer.render(yaml);
            RenderedForm previous = loaded.put(form.formKey(), form);
            if (previous != null) {
                throw new IllegalStateException("duplicate formKey on classpath: " + form.formKey());
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("no forms found at " + FORM_PATTERN);
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
