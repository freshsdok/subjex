package com.subjex.platform.app.declaration;

/** DeclarationPromoteNeedsSecondOperator — 晋升缺少第二名 declaration.promote 操作员确认。 */
public final class DeclarationPromoteNeedsSecondOperator extends RuntimeException {
    public DeclarationPromoteNeedsSecondOperator(String message) {
        super(message);
    }
}
