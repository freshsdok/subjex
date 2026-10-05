package com.subjex.platform.app.skin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.skin.NamedSkin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SkinApiEndpoint — 外观接口：每个具名外观的名字，以及它在 page-skin 样式表里的 CSS 变量。
 * <p>
 * {@code name} is the skin token, e.g. {@code high-contrast}; {@code variables} maps each CSS custom property
 * (e.g. {@code --page-text}) to its value. Both come from {@link NamedSkin#styleSheet()}, so there is no second copy.
 * Unlike {@link SkinPage}, the JSON does list the variables; it still sets no cookie.
 * {@code name} 是外观名字，例如 {@code high-contrast}；{@code variables} 把每个 CSS 自定义属性
 * （例如 {@code --page-text}）对应到它的值。两者都来自 {@link NamedSkin#styleSheet()}，不另存一份。
 * 和 {@link SkinPage} 不同，JSON 会列出变量；但它不写 cookie。
 */
@RestController
public class SkinApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/skins";

    @GetMapping(PATH)
    public SkinListDocument skins() {
        String sheet = NamedSkin.styleSheet();
        List<SkinDocument> skins = new ArrayList<>();
        for (NamedSkin skin : NamedSkin.values()) {
            skins.add(new SkinDocument(skin.token(), variables(sheet, skin.token())));
        }
        return new SkinListDocument(List.copyOf(skins));
    }

    /**
     * CSS variables in the {@code html[data-skin="<token>"]} block, in sheet order.
     * {@code html[data-skin="<名字>"]} 块里的 CSS 变量，按样式表顺序。
     */
    static Map<String, String> variables(String sheet, String token) {
        String selector = "html[data-skin=\"" + token + "\"]";
        int start = sheet.indexOf(selector);
        if (start < 0) {
            throw new IllegalStateException("style sheet has no block for " + token);
        }
        int open = sheet.indexOf('{', start);
        int close = sheet.indexOf('}', open);
        if (open < 0 || close < 0) {
            throw new IllegalStateException("style sheet block for " + token + " is incomplete");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (String declaration : sheet.substring(open + 1, close).split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String property = declaration.substring(0, colon).trim();
            if (property.startsWith("--")) {
                variables.put(property, declaration.substring(colon + 1).trim());
            }
        }
        return variables;
    }

    /**
     * SkinListDocument — 外观文档：具名外观。
     */
    public record SkinListDocument(List<SkinDocument> skins) {}

    /**
     * SkinDocument — 一个外观：名字（cookie 和查询里的值），以及 CSS 变量名到值的映射。
     */
    public record SkinDocument(String name, Map<String, String> variables) {}
}
