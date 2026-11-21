package com.subjex.platform.contract.exporthook;

/**
 * SubjectRecordExporter — pluggable SPI for a personal-data export bundle (ADR 0020 / WP8-3).
 * 个人数据导出包的可插拔 SPI。
 * <p>
 * The kernel ships one generic platform exporter and no domain (e.g. vertical) exporter.
 * Unconfigured production fails closed when no exporter supports the requested scope.
 * 内核仅内置通用平台导出器，不内置领域导出器。生产未配置且无导出器支持该范围时失败关闭。
 */
public interface SubjectRecordExporter {

    /**
     * Whether this exporter can produce a bundle for the scope — 是否能按该范围产出包.
     */
    boolean supports(String tenantId, String subjectId, String scopeDescriptor);

    /**
     * Build the subject's own bundle. Callers must have already passed {@link ExportApprovalGate}.
     * 构建该主体本人的数据包。调用方必须已通过导出审批门禁。
     */
    ExportArtifact export(String tenantId, String subjectId, String scopeDescriptor);
}
