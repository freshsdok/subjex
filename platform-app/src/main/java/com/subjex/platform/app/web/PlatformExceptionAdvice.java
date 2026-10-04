package com.subjex.platform.app.web;

import com.subjex.platform.app.task.IdempotencyConflict;
import com.subjex.platform.app.task.RateLimitExceeded;
import com.subjex.platform.app.task.SubmitLockHeld;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PlatformExceptionAdvice — 平台异常对应：把门禁和任务规则变成 HTTP 状态，不把库错误写回调用方。
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

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> rejected(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
    }
}
