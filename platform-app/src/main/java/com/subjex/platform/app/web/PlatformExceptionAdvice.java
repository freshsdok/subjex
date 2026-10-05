package com.subjex.platform.app.web;

import com.subjex.platform.app.form.FormProblemDocument;
import com.subjex.platform.app.form.FormValidationException;
import com.subjex.platform.app.security.DeclarationPermissionDeniedException;
import com.subjex.platform.app.task.IdempotencyConflict;
import com.subjex.platform.app.task.RateLimitExceeded;
import com.subjex.platform.app.task.SubmitLockHeld;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PlatformExceptionAdvice — 平台异常对应：把门禁和任务规则变成 HTTP 状态，不把库错误写回调用方。
 * <p>
 * Form submit validation and declaration permission denials return structured JSON for the console
 * debug panel. Other IllegalArgumentException / AccessDeniedException keep the previous shapes.
 * 表单校验与声明权限拒绝返回结构化 JSON，供控制台调试面板；其它非法参数 / 拒绝保持原形状。
 */
@RestControllerAdvice
public class PlatformExceptionAdvice {

    @ExceptionHandler(TenantMissingException.class)
    ResponseEntity<Void> missingTenant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(RateLimitExceeded.class)
    ResponseEntity<Void> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    @ExceptionHandler({IdempotencyConflict.class, SubmitLockHeld.class})
    ResponseEntity<Void> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(FormValidationException.class)
    ResponseEntity<FormProblemDocument> formValidation(FormValidationException ex) {
        return ResponseEntity.badRequest()
                .body(new FormProblemDocument("validation", ex.fieldErrors(), null, ex.getMessage()));
    }

    @ExceptionHandler(DeclarationPermissionDeniedException.class)
    ResponseEntity<FormProblemDocument> declarationPermissionDenied(DeclarationPermissionDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new FormProblemDocument(
                        "permission_denied", List.of(), ex.permission(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> rejected(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Void> accessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
