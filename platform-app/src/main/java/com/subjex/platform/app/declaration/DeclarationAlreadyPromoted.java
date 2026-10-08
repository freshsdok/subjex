package com.subjex.platform.app.declaration;

/**
 * DeclarationAlreadyPromoted — 该修订已晋升；再晋升返回 409。
 */
public final class DeclarationAlreadyPromoted extends RuntimeException {

    public DeclarationAlreadyPromoted(String message) {
        super(message);
    }
}
