package com.subjex.platform.contract.inbox;

/**
 * InboxSeverity — 站内信严重度：信息 / 警告 / 需点击处理。
 */
public enum InboxSeverity {

    /** Informational — 信息. */
    INFO,
    /** Warning — 警告. */
    WARN,
    /** Needs click-through / action — 需点进处理. */
    ACTION
}
