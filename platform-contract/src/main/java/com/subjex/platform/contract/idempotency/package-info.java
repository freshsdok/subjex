/**
 * Idempotency — 幂等：同一个租户里，同一枚记号只接受一次提交。
 * <p>
 * The single idempotency port. A repeated token returns the original task instead of inserting another.
 * 唯一的幂等端口。重复记号返回原来的任务，不再插入一条。
 */
package com.subjex.platform.contract.idempotency;
