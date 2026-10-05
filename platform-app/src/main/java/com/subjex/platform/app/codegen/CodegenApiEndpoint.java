package com.subjex.platform.app.codegen;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.form.FormCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CodegenApiEndpoint — 生成类型接口：{@link CodegenPage} 的 JSON 孪生，记录名和每个组件的类型。
 * <p>
 * Uses the same {@link FormCatalog} and the same agreement check as the page: the checked-in record and the
 * generator must write the same signature, otherwise the request fails. There is no endpoint that writes a file.
 * 用同一个 {@link FormCatalog}，做和页面同样的一致性检查：已检入的记录与生成器写出的签名必须一致，否则请求失败。
 * 没有写文件的接口。
 */
@RestController
public class CodegenApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/codegen";

    private final FormCatalog catalog;

    public CodegenApiEndpoint(FormCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(PATH)
    public CodegenDocument codegen() {
        CodegenPage.GeneratedType type = CodegenPage.GeneratedType.agree(catalog.publication());
        List<ComponentDocument> components = type.components().stream()
                .map(component -> new ComponentDocument(component.name(), component.typeEn(), component.typeZh()))
                .toList();
        return new CodegenDocument(type.recordName(), components);
    }

    /**
     * CodegenDocument — 生成类型文档：Java 记录简单名，以及按声明顺序排列的组件。
     */
    public record CodegenDocument(String recordName, List<ComponentDocument> components) {}

    /**
     * ComponentDocument — 一个组件：名字、Java 类型名（如 {@code String}）、中文类型词（如 字符串）。
     */
    public record ComponentDocument(String name, String javaType, String typeZh) {}
}
