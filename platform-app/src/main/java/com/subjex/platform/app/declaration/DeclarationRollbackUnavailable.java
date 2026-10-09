package com.subjex.platform.app.declaration;

/** DeclarationRollbackUnavailable — 没有上一份已晋升声明可回滚。 */
public final class DeclarationRollbackUnavailable extends RuntimeException {
    public DeclarationRollbackUnavailable(String message) {
        super(message);
    }
}
