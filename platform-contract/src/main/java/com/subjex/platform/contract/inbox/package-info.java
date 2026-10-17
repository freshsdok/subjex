/**
 * Inbox — 站内信：按收件人的产品内信箱（系统通知 + 工作流提醒），不是聊天，也不是出箱。
 * <p>
 * One message, N entries; unread via {@code read_at}; tenant fail-closed on every query.
 * 一条消息、N 条收件行；未读看 {@code read_at}；每次查询租户失败关闭。
 */
package com.subjex.platform.contract.inbox;
