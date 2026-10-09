package com.subjex.platform.app.capability;

import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CapabilityApiEndpoint — 能力目录与试跑：列出算法/AI 检入项；POST 对目录项跑桩（不写库）。
 * <p>
 * Needs {@code page.read}. {@code POST .../run} is for console try-run and flow-declared
 * {@code submit.capabilityId}; stubs are deterministic/preview only (no model-gateway).
 * 安全层要 {@code page.read}。试跑供控制台与流程声明的 capabilityId；仍为桩，不经真网关。
 */
@RestController
public class CapabilityApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/capabilities";

    private final CapabilityCatalog catalog;
    private final CapabilityRunner runner;

    public CapabilityApiEndpoint(CapabilityCatalog catalog, CapabilityRunner runner) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.runner = Objects.requireNonNull(runner, "runner");
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

    /**
     * Try-run a catalog capability — 对目录能力试跑。
     */
    @PostMapping(PATH + "/{capabilityId}/run")
    public CapabilityRunDocument run(
            @PathVariable("capabilityId") String capabilityId,
            @RequestBody(required = false) CapabilityRunRequest body) {
        if (body == null || body.inputText() == null) {
            throw new CapabilityRejected("inputText is required");
        }
        String result = runner.run(capabilityId, Map.of("inputText", body.inputText()));
        return new CapabilityRunDocument(capabilityId, result);
    }

    /** CapabilitiesDocument — 能力目录列表。 */
    public record CapabilitiesDocument(List<CapabilityDocument> capabilities) {}

    /** CapabilityDocument — 一项能力。 */
    public record CapabilityDocument(
            String id, String kind, String titleEn, String titleZh, String summaryEn, String summaryZh) {}

    /** CapabilityRunRequest — 试跑请求体。 */
    public record CapabilityRunRequest(String inputText) {}

    /** CapabilityRunDocument — 试跑结果。 */
    public record CapabilityRunDocument(String capabilityId, String result) {}
}
