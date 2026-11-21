package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectToken;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.questionnaire.QuestionnaireItemStats;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireStats;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * QuestionnaireEndpoint — 问卷 REST（Q6）：操作员编排 + 公开匿名采集。
 * <p>
 * Operator paths under {@code /api/v1/questionnaires/**}; public under {@code .../public/{token}}.
 * 操作员路径 {@code /api/v1/questionnaires/**}；公开路径 {@code .../public/{token}}。
 */
@RestController
public class QuestionnaireEndpoint {

    public static final String BASE = JsonApi.BASE + "/questionnaires";

    private final QuestionnaireService questionnaires;

    public QuestionnaireEndpoint(QuestionnaireService questionnaires) {
        this.questionnaires = Objects.requireNonNull(questionnaires, "questionnaires");
    }

    @GetMapping(BASE)
    public ListDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit) {
        requireOperator(operator);
        int lim = limit == null ? 50 : limit;
        List<DefinitionDocument> rows = questionnaires.list(operator, resolveTenant(tenantHeader, tenantIdParam), lim)
                .stream()
                .map(DefinitionDocument::from)
                .toList();
        return new ListDocument(rows);
    }

    @GetMapping(BASE + "/{questionnaireId}")
    public DefinitionDocument getOne(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return DefinitionDocument.from(
                questionnaires.get(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @PostMapping(BASE)
    public DefinitionDocument create(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody CreateRequest body) {
        requireOperator(operator);
        if (body == null || body.title() == null) {
            throw new IllegalArgumentException("title required");
        }
        QuestionnaireCollectMode mode = body.collectMode() == null
                ? QuestionnaireCollectMode.AUTH_END_USER
                : QuestionnaireCollectMode.valueOf(body.collectMode());
        return DefinitionDocument.from(
                questionnaires.create(operator, resolveTenant(tenantHeader, tenantIdParam), body.title(), mode));
    }

    @PutMapping(BASE + "/{questionnaireId}/items")
    public DefinitionDocument replaceItems(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody ItemsRequest body) {
        requireOperator(operator);
        if (body == null || body.items() == null) {
            throw new IllegalArgumentException("items required");
        }
        List<QuestionnaireItem> items = body.items().stream().map(ItemDocument::toItem).toList();
        return DefinitionDocument.from(questionnaires.replaceItems(
                operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId, items));
    }

    @PostMapping(BASE + "/{questionnaireId}/publish")
    public DefinitionDocument publish(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return DefinitionDocument.from(
                questionnaires.publish(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @PostMapping(BASE + "/{questionnaireId}/close")
    public DefinitionDocument close(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return DefinitionDocument.from(
                questionnaires.close(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @GetMapping(BASE + "/{questionnaireId}/responses")
    public ResponsesDocument listResponses(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "includePreview", required = false, defaultValue = "false") boolean includePreview,
            @RequestParam(value = "limit", required = false) Integer limit) {
        requireOperator(operator);
        int lim = limit == null ? 50 : limit;
        List<ResponseDocument> rows = questionnaires
                .listResponses(
                        operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId, includePreview, lim)
                .stream()
                .map(ResponseDocument::from)
                .toList();
        return new ResponsesDocument(rows);
    }

    @GetMapping(BASE + "/{questionnaireId}/stats")
    public StatsDocument stats(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return StatsDocument.from(
                questionnaires.stats(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @GetMapping(BASE + "/{questionnaireId}/export.csv")
    public ResponseEntity<String> exportCsv(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        String csv = questionnaires.exportCsv(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"questionnaire-export.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    @GetMapping(BASE + "/{questionnaireId}/collect-token")
    public TokenDocument activeToken(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return TokenDocument.from(
                questionnaires.activeToken(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @PostMapping(BASE + "/{questionnaireId}/collect-token")
    public TokenDocument issueToken(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return TokenDocument.from(
                questionnaires.issueToken(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @PostMapping(BASE + "/{questionnaireId}/preview-responses")
    public ResponseDocument previewSubmit(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody AnswersRequest body) {
        requireOperator(operator);
        if (body == null || body.answers() == null) {
            throw new IllegalArgumentException("answers required");
        }
        List<QuestionnaireAnswer> answers =
                body.answers().stream().map(a -> new QuestionnaireAnswer(a.itemId(), a.valueJson())).toList();
        return ResponseDocument.from(questionnaires.previewSubmit(
                operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId, answers));
    }

    @GetMapping(BASE + "/public/{tokenId}")
    public DefinitionDocument publicGet(@PathVariable("tokenId") String tokenId) {
        return DefinitionDocument.from(questionnaires
                .publicGet(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "collect token not found")));
    }

    @PostMapping(BASE + "/public/{tokenId}/responses")
    public ResponseDocument publicSubmit(
            @PathVariable("tokenId") String tokenId,
            @RequestHeader(value = "X-Client-Key", required = false) String clientKeyHeader,
            @RequestBody PublicSubmitRequest body) {
        if (body == null || body.answers() == null) {
            throw new IllegalArgumentException("answers required");
        }
        String clientKey = clientKeyHeader != null && !clientKeyHeader.isBlank()
                ? clientKeyHeader
                : body.clientKey();
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("clientKey required");
        }
        List<QuestionnaireAnswer> answers =
                body.answers().stream().map(a -> new QuestionnaireAnswer(a.itemId(), a.valueJson())).toList();
        return ResponseDocument.from(questionnaires
                .publicSubmit(tokenId, clientKey, answers)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "collect token not found")));
    }

    private static void requireOperator(OperatorPrincipal operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        throw new IllegalArgumentException("tenantId required");
    }

    public record CreateRequest(String title, String collectMode) {}

    public record ItemsRequest(List<ItemDocument> items) {}

    public record AnswersRequest(List<AnswerDocument> answers) {}

    public record PublicSubmitRequest(String clientKey, List<AnswerDocument> answers) {}

    public record AnswerDocument(String itemId, String valueJson) {}

    public record ListDocument(List<DefinitionDocument> questionnaires) {}

    public record ResponsesDocument(List<ResponseDocument> responses) {}

    public record ItemDocument(
            String itemId, int sortOrder, String questionType, String prompt, boolean required, String optionsJson) {
        static ItemDocument from(QuestionnaireItem item) {
            return new ItemDocument(
                    item.itemId(),
                    item.sortOrder(),
                    item.questionType().name(),
                    item.prompt(),
                    item.required(),
                    item.optionsJson());
        }

        QuestionnaireItem toItem() {
            return new QuestionnaireItem(
                    itemId,
                    sortOrder,
                    com.subjex.platform.contract.questionnaire.QuestionType.valueOf(questionType),
                    prompt,
                    required,
                    optionsJson);
        }
    }

    public record DefinitionDocument(
            String questionnaireId,
            String tenantId,
            String title,
            String status,
            int revision,
            String collectMode,
            List<ItemDocument> items,
            Instant createdAt,
            Instant updatedAt) {
        static DefinitionDocument from(QuestionnaireDefinition def) {
            return new DefinitionDocument(
                    def.questionnaireId(),
                    def.tenantId(),
                    def.title(),
                    def.status().name(),
                    def.revision(),
                    def.collectMode().name(),
                    def.items().stream().map(ItemDocument::from).toList(),
                    def.createdAt(),
                    def.updatedAt());
        }
    }

    public record ResponseDocument(
            String responseId,
            String questionnaireId,
            int revision,
            String tenantId,
            String respondentRealm,
            String respondentSubjectId,
            Instant submittedAt,
            boolean countsForStats,
            List<AnswerDocument> answers) {
        static ResponseDocument from(QuestionnaireResponse response) {
            return new ResponseDocument(
                    response.responseId(),
                    response.questionnaireId(),
                    response.revision(),
                    response.tenantId(),
                    response.respondentRealm().name(),
                    response.respondentSubjectId(),
                    response.submittedAt(),
                    response.countsForStats(),
                    response.answers().stream()
                            .map(a -> new AnswerDocument(a.itemId(), a.valueJson()))
                            .toList());
        }
    }

    public record ItemStatsDocument(String itemId, String questionType, String prompt, Map<String, Long> counts) {
        static ItemStatsDocument from(QuestionnaireItemStats stats) {
            return new ItemStatsDocument(
                    stats.itemId(), stats.questionType().name(), stats.prompt(), stats.counts());
        }
    }

    public record StatsDocument(
            String questionnaireId,
            String tenantId,
            int revision,
            long responseCount,
            List<ItemStatsDocument> items) {
        static StatsDocument from(QuestionnaireStats stats) {
            return new StatsDocument(
                    stats.questionnaireId(),
                    stats.tenantId(),
                    stats.revision(),
                    stats.responseCount(),
                    stats.items().stream().map(ItemStatsDocument::from).toList());
        }
    }

    public record TokenDocument(
            String tokenId, String questionnaireId, String tenantId, Instant createdAt, Instant revokedAt) {
        static TokenDocument from(QuestionnaireCollectToken token) {
            return new TokenDocument(
                    token.tokenId(),
                    token.questionnaireId(),
                    token.tenantId(),
                    token.createdAt(),
                    token.revokedAt());
        }
    }
}
