/**
 * Rate limit — 限流：同一租户对同一动作在一个时间窗里只能通过有限次数。
 * <p>
 * The single rate-limit port. Default is in-process; platform-app may share counters via JDBC
 * ({@code platform.rate-limit.backend=jdbc}). The port itself does not mention a cache product.
 * 唯一的限流端口。默认进程内计数；platform-app 可用 JDBC 共享计数。端口本身不绑定某种缓存产品。
 */
package com.subjex.platform.contract.ratelimit;
