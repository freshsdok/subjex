/**
 * Outbox socket — 出箱套接字：平台把出箱行送到另一个应用的产品帧。
 * <p>
 * The frame is the delivery path shared by platform-app and sample-consumer. It is not an in-process call.
 * 这个帧是 platform-app 与 sample-consumer 共用的投递路径。它不是进程内调用。
 */
package com.subjex.platform.contract.delivery;
