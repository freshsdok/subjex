package com.subjex.platform.app.form;

import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FormsApiEndpoint — 字段列表接口：{@link FormsPage} 的 JSON 孪生，表单标题和每个字段的名字、类型、是否必填。
 * <p>
 * Read from the same {@link FormCatalog}. This is not a form designer; nothing here changes the YAML.
 * 读的是同一个 {@link FormCatalog}。这不是表单设计器，这里不改 YAML。
 */
@RestController
public class FormsApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/forms";

    private final FormCatalog catalog;

    public FormsApiEndpoint(FormCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(PATH)
    public FormsDocument forms() {
        RenderedForm form = catalog.publication();
        List<FieldDocument> fields = form.fields().stream()
                .map(field -> new FieldDocument(
                        field.name(),
                        field.kind().name().toLowerCase(Locale.ROOT),
                        field.required()))
                .toList();
        return new FormsDocument(form.formKey(), form.titleZh(), form.titleEn(), fields);
    }

    /**
     * FormsDocument — 字段列表文档：表单键、中文标题、英文标题，以及字段。
     */
    public record FormsDocument(String formKey, String titleZh, String titleEn, List<FieldDocument> fields) {}

    /**
     * FieldDocument — 一个字段：名字、类型词（字段种类的小写名）、是否必填。
     */
    public record FieldDocument(String name, String kind, boolean required) {}
}
