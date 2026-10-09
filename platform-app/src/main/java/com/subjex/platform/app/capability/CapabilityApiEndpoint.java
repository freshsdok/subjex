package com.subjex.platform.app.capability;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CapabilityApiEndpoint — 能力目录、试跑预览、AI 写回确认票与写回（须 consume 票）。
 * <p>
 * Needs {@code page.read}. AI preview via {@link ModelCompletionClient}; write-back is fail-closed without
 * a valid {@link AiWriteConfirmGate} ticket. Default sink is noop (no entity/DB mutation).
 * 要 {@code page.read}。AI 预览经补全客户端；无有效确认票不得写回。默认落点 noop。
 */
@RestController
public class CapabilityApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/capabilities";

    public static final String AUDIT_PREVIEW = "capability.ai.preview";
    public static final String AUDIT_CONFIRM = "capability.ai.confirm";

    private final CapabilityCatalog catalog;
    private final CapabilityRunner runner;
    private final ModelCompletionClient completionClient;
    private final AiWriteConfirmGate confirmGate;
    private final AiWriteBackSink writeBackSink;
    private final OperatorActionAudit audit;

    public CapabilityApiEndpoint(
            CapabilityCatalog catalog,
            CapabilityRunner runner,
            ModelCompletionClient completionClient,
            AiWriteConfirmGate confirmGate,
            AiWriteBackSink writeBackSink,
            OperatorActionAudit audit) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.completionClient = Objects.requireNonNull(completionClient, "completionClient");
        this.confirmGate = Objects.requireNonNull(confirmGate, "confirmGate");
        this.writeBackSink = Objects.requireNonNull(writeBackSink, "writeBackSink");
        this.audit = Objects.requireNonNull(audit, "audit");
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
     * Try-run / preview — 试跑预览（不写库）。AI 记 {@code capability.ai.preview}。
     */
    @PostMapping(PATH + "/{capabilityId}/run")
    public CapabilityRunDocument run(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("capabilityId") String capabilityId,
            @RequestBody(required = false) CapabilityRunRequest body) {
        if (body == null || body.inputText() == null) {
            throw new CapabilityRejected("inputText is required");
        }
        CapabilityId id = CapabilityId.parse(catalog.require(capabilityId).id());
        String result = runner.run(capabilityId, Map.of("inputText", body.inputText()));
        if (id.kind() == CapabilityKind.AI && operator != null) {
            audit.record(operator, AUDIT_PREVIEW, id.id(), AuditOutcome.ALLOWED);
        }
        return new CapabilityRunDocument(capabilityId, result);
    }

    /**
     * Issue a one-shot AI write-back confirm ticket after preview — 预览后签发一次性写回确认票（AI only）。
     */
    @PostMapping(PATH + "/{capabilityId}/write-ticket")
    public WriteTicketDocument writeTicket(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("capabilityId") String capabilityId,
            @RequestBody(required = false) CapabilityRunRequest body) {
        requireOperator(operator);
        if (body == null || body.inputText() == null || body.inputText().isBlank()) {
            throw new CapabilityRejected("inputText is required");
        }
        CapabilityId id = requireAi(capabilityId);
        ModelCompletionRequest request = new ModelCompletionRequest(id.id(), body.inputText().strip());
        ModelCompletionResult preview = completionClient.complete(request);
        AiWriteConfirmTicket ticket = confirmGate.issue(request, preview);
        audit.record(operator, AUDIT_PREVIEW, id.id() + "#" + ticket.ticketId(), AuditOutcome.ALLOWED);
        return new WriteTicketDocument(
                id.id(),
                preview.text(),
                ticket.ticketId(),
                ticket.inputDigest(),
                ticket.previewDigest(),
                ticket.expiresAt());
    }

    /**
     * Consume ticket and apply write-back sink (default noop) — 消费确认票并走写回落点（默认 noop）。
     */
    @PostMapping(PATH + "/{capabilityId}/write-back")
    public WriteBackDocument writeBack(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("capabilityId") String capabilityId,
            @RequestBody(required = false) WriteBackRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new CapabilityRejected("write-back body required");
        }
        CapabilityId id = requireAi(capabilityId);
        if (body.ticketId() == null
                || body.inputDigest() == null
                || body.previewDigest() == null
                || body.expiresAt() == null
                || body.previewText() == null) {
            throw new CapabilityRejected("ticketId, inputDigest, previewDigest, expiresAt, previewText required");
        }
        AiWriteConfirmTicket ticket = new AiWriteConfirmTicket(
                body.ticketId().strip(),
                id.id(),
                body.inputDigest().strip(),
                body.previewDigest().strip(),
                body.expiresAt());
        confirmGate.consume(ticket);
        AiWriteBackResult applied = writeBackSink.apply(id.id(), body.previewText(), ticket);
        audit.record(operator, AUDIT_CONFIRM, id.id() + "#" + ticket.ticketId(), AuditOutcome.ALLOWED);
        return new WriteBackDocument(
                id.id(),
                ticket.ticketId(),
                applied.sink(),
                applied.suggestion(),
                applied.persisted());
    }

    private static CapabilityId requireAi(String capabilityId) {
        CapabilityId id = CapabilityId.parse(capabilityId);
        if (id.kind() != CapabilityKind.AI) {
            throw new CapabilityRejected("write-back is only for AI capabilities (got " + id.id() + ")");
        }
        return id;
    }

    private static void requireOperator(OperatorPrincipal operator) {
        if (operator == null) {
            throw new CapabilityRejected("operator required");
        }
    }

    /** CapabilitiesDocument — 能力目录列表。 */
    public record CapabilitiesDocument(List<CapabilityDocument> capabilities) {}

    /** CapabilityDocument — 一项能力。 */
    public record CapabilityDocument(
            String id, String kind, String titleEn, String titleZh, String summaryEn, String summaryZh) {}

    /** CapabilityRunRequest — 试跑/签票请求体。 */
    public record CapabilityRunRequest(String inputText) {}

    /** CapabilityRunDocument — 试跑结果。 */
    public record CapabilityRunDocument(String capabilityId, String result) {}

    /** WriteTicketDocument — 已签发的写回确认票。 */
    public record WriteTicketDocument(
            String capabilityId,
            String previewText,
            String ticketId,
            String inputDigest,
            String previewDigest,
            Instant expiresAt) {}

    /** WriteBackRequest — 写回请求（须带完整票字段）。 */
    public record WriteBackRequest(
            String ticketId,
            String inputDigest,
            String previewDigest,
            Instant expiresAt,
            String previewText) {}

    /** WriteBackDocument — 写回结果。 */
    public record WriteBackDocument(
            String capabilityId, String ticketId, String sink, String suggestion, boolean persisted) {}
}
