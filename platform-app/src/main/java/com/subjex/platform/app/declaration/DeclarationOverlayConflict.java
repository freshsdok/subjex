package com.subjex.platform.app.declaration;

/**
 * DeclarationOverlayConflict — 声明覆盖冲突：草稿改了表名或主键，运行时拒绝覆盖（须经迁移/晋升）。
 */
public final class DeclarationOverlayConflict extends RuntimeException {

    public DeclarationOverlayConflict(String message) {
        super(message);
    }
}
