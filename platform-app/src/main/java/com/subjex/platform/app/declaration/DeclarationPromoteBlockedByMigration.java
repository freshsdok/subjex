package com.subjex.platform.app.declaration;

/**
 * DeclarationPromoteBlockedByMigration — 实体晋升被同修订未 APPLIED/CANCELLED 的迁移挡住；HTTP 409。
 */
public final class DeclarationPromoteBlockedByMigration extends RuntimeException {

    public DeclarationPromoteBlockedByMigration(String message) {
        super(message);
    }
}
