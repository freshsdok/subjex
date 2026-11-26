/**
 * Payment — 统一支付端口：创单、幂等回调、退款（含部分退款）、查单与平台侧凭证台账，不是财务中台。
 * <p>
 * Domain modules create, apply results, cancel a pending payment, refund, and query payment documents here. Tenant fail-closed;
 * reserved {@code platform} is never a default. Cross-tenant access is denied. Amounts are integer
 * minor units plus an explicit ISO 4217 currency. Status changes write {@code PaymentStatusChanged}
 * on the existing {@code outbox_event} bus. Phase 1 uses a mock adapter; {@code WECHAT_PAY} / {@code ALIPAY}
 * are reserved identifiers only.
 * 域模块在此创单、入账、取消待支付、退款并查单。租户失败关闭；保留租户 {@code platform} 绝不是默认值。禁止跨租户访问。
 * 金额为最小货币单位整数 + 显式 ISO 4217 币种。状态变化写在已有出箱总线上。首期使用模拟适配器；微信支付/支付宝只是预留标识。
 */
package com.subjex.platform.contract.payment;
