package com.subjex.platform.contract.exporthook;

/**
 * ExportArtifactUnavailableException — no READY artifact is available (not generated, rejected, or unapproved).
 * 没有可用的 READY 产物（未生成、已驳回或未批准）。
 */
public class ExportArtifactUnavailableException extends RuntimeException {

    public static final String REASON = "export-artifact-unavailable";

    public ExportArtifactUnavailableException() {
        super(REASON);
    }
}
