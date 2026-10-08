package com.subjex.platform.app.declaration;

/**
 * DeclarationMigrationNotReady — 迁移尚未 REVIEWED（或状态不对），不能执行；HTTP 409。
 */
public final class DeclarationMigrationNotReady extends RuntimeException {

    public DeclarationMigrationNotReady(String message) {
        super(message);
    }
}
