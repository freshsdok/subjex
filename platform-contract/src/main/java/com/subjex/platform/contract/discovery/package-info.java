/**
 * Service discovery — 服务发现：按服务名登记和解析一个端点。
 * <p>
 * platform-app keeps the registry and serves it over HTTP. sample-consumer registers and resolves through that HTTP port.
 * When the HTTP registry cannot be reached, static host and port are the fallback.
 * There is no Nacos, Eureka, or Consul server.
 * platform-app 保存登记簿并用 HTTP 提供出来。sample-consumer 通过这个 HTTP 端口登记和解析。
 * HTTP 登记簿连不上时，用静态主机和端口兜底。
 * 没有 Nacos、Eureka 或 Consul 服务器。
 */
package com.subjex.platform.contract.discovery;
