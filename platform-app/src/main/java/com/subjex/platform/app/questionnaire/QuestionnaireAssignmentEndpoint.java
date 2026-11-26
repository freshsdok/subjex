package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * QuestionnaireAssignmentEndpoint — operator assignment list/add/remove under a questionnaire.
 * 操作员在问卷下列出、添加、移除分配。复用 questionnaire.read / questionnaire.write。
 */
@RestController
public class QuestionnaireAssignmentEndpoint {

    public static final String PATH = QuestionnaireEndpoint.BASE + "/{questionnaireId}/assignments";

    public static final String ITEM_PATH = PATH + "/{assignmentId}";

    private final QuestionnaireAssignmentService assignments;

    public QuestionnaireAssignmentEndpoint(QuestionnaireAssignmentService assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @GetMapping(PATH)
    public AssignmentListDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        List<AssignmentDocument> rows = assignments
                .list(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId)
                .stream()
                .map(AssignmentDocument::from)
                .toList();
        return new AssignmentListDocument(rows);
    }

    @PostMapping(PATH)
    public AssignmentDocument add(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) AssignmentRequest body) {
        requireOperator(operator);
        String kind = body == null ? null : body.assigneeKind();
        String assigneeId = body == null ? null : body.assigneeId();
        return AssignmentDocument.from(assignments.add(
                operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId, kind, assigneeId));
    }

    @DeleteMapping(ITEM_PATH)
    public ResponseEntity<Void> remove(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("questionnaireId") String questionnaireId,
            @PathVariable("assignmentId") String assignmentId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        assignments.remove(operator, resolveTenant(tenantHeader, tenantIdParam), questionnaireId, assignmentId);
        return ResponseEntity.noContent().build();
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

    public record AssignmentRequest(String assigneeKind, String assigneeId) {}

    public record AssignmentListDocument(List<AssignmentDocument> assignments) {}

    public record AssignmentDocument(
            String assignmentId,
            String questionnaireId,
            String assigneeKind,
            String assigneeId,
            Instant createdAt) {
        static AssignmentDocument from(QuestionnaireAssignment assignment) {
            return new AssignmentDocument(
                    assignment.assignmentId(),
                    assignment.questionnaireId(),
                    assignment.assigneeKind().name(),
                    assignment.assigneeId(),
                    assignment.createdAt());
        }
    }
}
