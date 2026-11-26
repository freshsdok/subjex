package com.subjex.platform.contract.exporthook;

/**
 * ExportRequestStatus — request lifecycle including background generation.
 * 导出申请生命周期，含后台生成。
 * <p>
 * After approval the request is enqueued as {@link #GENERATING} and a thin worker produces the
 * artifact (or {@link #FAILED}). {@link #READY} artifacts still expire after 7 days.
 * 批准后申请入队为 {@link #GENERATING}，由薄工作线程生成产物（失败则为 {@link #FAILED}）。
 * {@link #READY} 产物仍在 7 日后过期。
 */
public enum ExportRequestStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    /** Queued or running in the background worker / 已入队或正在后台生成 */
    GENERATING,
    /** Background generation failed; may be retried / 后台生成失败，可再次入队 */
    FAILED,
    READY,
    EXPIRED
}
