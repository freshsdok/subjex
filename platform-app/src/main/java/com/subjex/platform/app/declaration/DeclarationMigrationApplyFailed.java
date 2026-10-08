package com.subjex.platform.app.declaration;

/**
 * DeclarationMigrationApplyFailed — DDL 执行失败（已 markFailed）；HTTP 500。
 */
public final class DeclarationMigrationApplyFailed extends RuntimeException {

    public DeclarationMigrationApplyFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
