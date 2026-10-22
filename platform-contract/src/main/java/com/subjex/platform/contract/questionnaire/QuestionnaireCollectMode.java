package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireCollectMode — 采集模式：登录终端用户 / 匿名链接（ADR 0013 §D5）。
 * <p>
 * Operator preview is an API concern (AUTH_OPERATOR_PREVIEW), not a stored collect mode on the definition.
 * 操作员预览由 API 表达，不写入定义上的采集模式。
 */
public enum QuestionnaireCollectMode {
    /** Logged-in end users via Bearer / OIDC — 已登录终端用户。 */
    AUTH_END_USER,
    /** Public link with opaque collect token — 匿名采集令牌链接。 */
    ANON_LINK
}
