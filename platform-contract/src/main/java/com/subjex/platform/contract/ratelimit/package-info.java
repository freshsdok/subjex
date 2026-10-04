/**
 * Rate limit — 限流：同一租户对同一动作在一个时间窗里只能通过有限次数。
 * <p>
 * The single rate-limit port. v1 counts inside one process; the port itself does not mention a cache product.
 * 唯一的限流端口。第一版在单进程内计数；端口本身不绑定某种缓存产品。
 */
package com.subjex.platform.contract.ratelimit;
