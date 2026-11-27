package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectToken;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponseListQuery;
import com.subjex.platform.contract.questionnaire.QuestionnaireStats;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * QuestionnaireService — 操作员问卷用例：租户授权 + 端口调用（Q6）。
 * <p>
 * Public anon collect stays on the endpoint (token + rate limit in port).
 * 公开匿名采集仍在 Endpoint（令牌+限流在端口内）。
 */
public final class QuestionnaireService {

    private final QuestionnairePort port;
    private final OperatorTenantAccess tenantAccess;
    private final TenantGuard tenantGuard;

    public QuestionnaireService(
            QuestionnairePort port, OperatorTenantAccess tenantAccess, TenantGuard tenantGuard) {
        this.port = Objects.requireNonNull(port, "port");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
    }

    public List<QuestionnaireDefinition> list(OperatorPrincipal operator, String tenantId, int limit) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        return port.list(tid, limit);
    }

    public QuestionnaireDefinition get(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        return port.get(tid, questionnaireId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
    }

    public QuestionnaireDefinition create(
            OperatorPrincipal operator, String tenantId, String title, QuestionnaireCollectMode collectMode) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        return port.create(tid, title, collectMode);
    }

    public QuestionnaireDefinition replaceItems(
            OperatorPrincipal operator, String tenantId, String questionnaireId, List<QuestionnaireItem> items) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        return port.replaceItems(tid, questionnaireId, items)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
    }

    public QuestionnaireDefinition publish(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        try {
            return port.publish(tid, questionnaireId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public QuestionnaireDefinition close(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        try {
            return port.close(tid, questionnaireId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public List<QuestionnaireResponse> listResponses(
            OperatorPrincipal operator, String tenantId, String questionnaireId, boolean includePreview, int limit) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        QuestionnaireResponseListQuery query = includePreview
                ? QuestionnaireResponseListQuery.includingPreview(limit)
                : QuestionnaireResponseListQuery.production(limit);
        return port.listResponses(tid, questionnaireId, query);
    }

    public QuestionnaireStats stats(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        return port.stats(tid, questionnaireId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
    }

    /**
     * Auth and existence check so the HTTP layer can 404 before starting the body.
     * 先做授权与存在性检查，便于 HTTP 层在开始写响应体之前返回 404。
     */
    public void requireCsvExportable(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        port.get(tid, questionnaireId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
    }

    /**
     * Stream the production CSV into {@code out}. Does not first build one String.
     * 把生产答卷 CSV 流式写入 {@code out}，不先拼成一整段字符串。
     */
    public void writeCsv(OperatorPrincipal operator, String tenantId, String questionnaireId, Appendable out) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        try {
            if (!port.writeCsv(tid, questionnaireId, out)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("questionnaire CSV export failed", ex);
        }
    }

    public QuestionnaireCollectToken activeToken(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        return port.activeCollectToken(tid, questionnaireId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "collect token not found"));
    }

    public QuestionnaireCollectToken issueToken(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        try {
            return port.issueCollectToken(tid, questionnaireId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public QuestionnaireResponse previewSubmit(
            OperatorPrincipal operator,
            String tenantId,
            String questionnaireId,
            List<QuestionnaireAnswer> answers) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        try {
            return port.submit(
                            tid,
                            questionnaireId,
                            QuestionnaireRespondentRealm.OPERATOR,
                            operator.subjectId(),
                            answers)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public Optional<QuestionnaireDefinition> publicGet(String tokenId) {
        return port.getByCollectToken(tokenId);
    }

    public Optional<QuestionnaireResponse> publicSubmit(
            String tokenId, String clientKey, List<QuestionnaireAnswer> answers) {
        return port.submitAnon(tokenId, clientKey, answers);
    }

    private String requireTenantAndGrant(OperatorPrincipal operator, String tenantId) {
        Objects.requireNonNull(operator, "operator");
        tenantGuard.requireTenant(tenantId);
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static void requirePermission(OperatorPrincipal operator, String permission) {
        if (!operator.permissionNames().contains(permission)) {
            throw new AccessDeniedException("missing permission " + permission);
        }
    }
}
