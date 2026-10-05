package com.subjex.platform.app.form;

import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * FormCatalog — 表单目录：从 classpath 上的声明式表单读出校验过的字段列表。
 * <p>
 * The YAML is the one shipped by {@code form-render}. The page does not keep a second
 * copy of the field names, types, or required flags.
 * YAML 就是 {@code form-render} 带上的那一份。页面不另存一份字段名、类型或是否必填。
 */
@Component
public class FormCatalog {

    /** Classpath form — classpath 上的表单。 */
    static final String RESOURCE = "/forms/endpoint-publication.form.yaml";

    public RenderedForm publication() {
        return load();
    }

    static RenderedForm load() {
        return new FormRenderer().render(text());
    }

    static String text() {
        try (InputStream in = FormCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
