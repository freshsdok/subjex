package com.subjex.platform.contract.exporthook;

/**
 * ExportExpiredException — the READY artifact's {@code expiresAt} has passed.
 * READY 产物已过 {@code expiresAt}。
 */
public class ExportExpiredException extends RuntimeException {

    public static final String REASON = "export-expired";

    public ExportExpiredException() {
        super(REASON);
    }
}
