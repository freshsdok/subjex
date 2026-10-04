/**
 * Task and message — 任务与消息：确定性步骤和非确定性步骤共用一张任务表、同一个端口。
 * <p>
 * A deterministic task is a retryable step whose outcome can be checked. A non-deterministic task adds exactly three facts: model id, input digest, and human confirmation. Unconfirmed is not complete.
 * 确定性任务是可重试、可核对的步骤。非确定性任务只多三件事实：模型标识、输入摘要、人工确认。未确认不算完成。
 */
package com.subjex.platform.contract.task;
