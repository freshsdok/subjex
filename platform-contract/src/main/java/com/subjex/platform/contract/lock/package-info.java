/**
 * Lock — 锁：同一时刻只允许一个持有者进入同一段平台动作。
 * <p>
 * platform-app backs the port with one database row (owner and expiry). Tests may still use a single-process map.
 * platform-app 用数据库的一行（持有者与到期时间）托底。测试仍可用单进程表。
 */
package com.subjex.platform.contract.lock;
