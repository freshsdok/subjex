package com.subjex.platform.app.capability;

import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CapabilityApiEndpoint — 能力目录只读 JSON：列出算法/AI 检入项（id、kind、标题）。
 * <p>
 * Needs {@code page.read} at the security layer. No invoke endpoint in this thin slice
 * (forms bind via domain actions {@code capability.algo.*} / {@code capability.ai.*}).
 * 安全层要 {@code page.read}。本薄片无直接调用接口（表单经领域动作挂接）。
 */
@RestController
public class CapabilityApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/capabilities";

    private final CapabilityCatalog catalog;

    public CapabilityApiEndpoint(CapabilityCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @GetMapping(PATH)
    public CapabilitiesDocument index() {
        List<CapabilityDocument> capabilities = catalog.list().stream()
                .map(spec -> new CapabilityDocument(
                        spec.id(),
                        spec.kind().name(),
                        spec.titleEn(),
                        spec.titleZh(),
                        spec.summaryEn(),
                        spec.summaryZh()))
                .toList();
        return new CapabilitiesDocument(capabilities);
    }

    /** CapabilitiesDocument — 能力目录列表。 */
    public record CapabilitiesDocument(List<CapabilityDocument> capabilities) {}

    /** CapabilityDocument — 一项能力。 */
    public record CapabilityDocument(
            String id, String kind, String titleEn, String titleZh, String summaryEn, String summaryZh) {}
}
