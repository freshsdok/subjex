package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserPrincipals;
import com.subjex.platform.app.identity.enduser.EndUserShellService;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserShellQuestionnaireEndpoint — WP7-1 list/fetch/submit {@code AUTH_END_USER} questionnaires.
 * 终端问卷：列出/读取/提交当前租户已发布的认证终端问卷；答卷人记为终端主体。
 */
@RestController
public class EndUserShellQuestionnaireEndpoint {

    /** JSON base for end-user questionnaires — 终端问卷 JSON 根路径. */
    public static final String BASE = JsonApi.BASE + "/end-user/questionnaires";

    private final EndUserShellService shell;

    public EndUserShellQuestionnaireEndpoint(EndUserShellService shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    @GetMapping(BASE)
    public QuestionnaireEndpoint.ListDocument list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        List<QuestionnaireEndpoint.DefinitionDocument> items = shell
                .listQuestionnaires(principal, resolveTenant(tenantHeader, tenantIdParam))
                .stream()
                .map(QuestionnaireEndpoint.DefinitionDocument::from)
                .toList();
        return new QuestionnaireEndpoint.ListDocument(items);
    }

    @GetMapping(BASE + "/{questionnaireId}")
    public QuestionnaireEndpoint.DefinitionDocument get(
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return QuestionnaireEndpoint.DefinitionDocument.from(
                shell.getQuestionnaire(principal, resolveTenant(tenantHeader, tenantIdParam), questionnaireId));
    }

    @PostMapping(BASE + "/{questionnaireId}/responses")
    public QuestionnaireEndpoint.ResponseDocument submit(
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody QuestionnaireEndpoint.AnswersRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        List<QuestionnaireAnswer> answers = body == null || body.answers() == null
                ? List.of()
                : body.answers().stream()
                        .map(item -> new QuestionnaireAnswer(item.itemId(), item.valueJson()))
                        .toList();
        return QuestionnaireEndpoint.ResponseDocument.from(shell.submitQuestionnaire(
                principal, resolveTenant(tenantHeader, tenantIdParam), questionnaireId, answers));
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }
}
