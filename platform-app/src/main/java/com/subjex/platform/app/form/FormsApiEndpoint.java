package com.subjex.platform.app.form;

import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * FormsApiEndpoint — 表单接口：目录索引列出每一份表单的键与标题；详情给出字段名、类型、是否必填。
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
    public FormsIndexDocument index() {
        List<FormIndexDocument> forms = catalog.list().stream()
                .map(form -> new FormIndexDocument(form.formKey(), form.titleZh(), form.titleEn()))
                .toList();
        return new FormsIndexDocument(forms);
    }

    @GetMapping(PATH + "/{formKey}")
    public FormsDocument detail(@PathVariable("formKey") String formKey) {
        RenderedForm form = catalog.require(formKey);
        List<FieldDocument> fields = form.fields().stream()
                .map(field -> new FieldDocument(
                        field.name(),
                        field.kind().name().toLowerCase(Locale.ROOT),
                        field.required()))
                .toList();
        return new FormsDocument(form.formKey(), form.titleZh(), form.titleEn(), fields);
    }

    /**
     * FormsIndexDocument — 表单目录：每一份表单的键与中英标题。
     */
    public record FormsIndexDocument(List<FormIndexDocument> forms) {}

    /**
     * FormIndexDocument — 目录中的一项：表单键、中文标题、英文标题。
     */
    public record FormIndexDocument(String formKey, String titleZh, String titleEn) {}

    /**
     * FormsDocument — 表单详情：表单键、中文标题、英文标题，以及字段。
     */
    public record FormsDocument(String formKey, String titleZh, String titleEn, List<FieldDocument> fields) {}

    /**
     * FieldDocument — 一个字段：名字、类型词（字段种类的小写名）、是否必填。
     */
    public record FieldDocument(String name, String kind, boolean required) {}
}
