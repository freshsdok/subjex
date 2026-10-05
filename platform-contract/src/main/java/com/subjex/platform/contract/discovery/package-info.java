/**
 * Service discovery — 服务发现：按服务名登记和解析一个端点。
 * <p>
 * platform-app keeps endpoints in the shared {@code service_endpoint} table and serves them over HTTP.
 * sample-consumer registers and resolves through that HTTP port. When the HTTP registry cannot be reached,
 * static host and port are the fallback. Tests may still use an in-process table. There is no Nacos, Eureka, or Consul server.
 * platform-app 把端点留在共享的 {@code service_endpoint} 表里，再用 HTTP 提供出来。
 * sample-consumer 通过这个 HTTP 端口登记和解析。HTTP 登记簿连不上时，用静态主机和端口兜底。
 * 测试仍可用进程内表。没有 Nacos、Eureka 或 Consul 服务器。
 */
package com.subjex.platform.contract.discovery;
