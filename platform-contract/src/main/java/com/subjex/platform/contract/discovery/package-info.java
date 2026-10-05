/**
 * Service discovery — 服务发现：按服务名登记和解析一个端点。
 * <p>
 * The port is in-process. A static configuration entry is the fallback when the registry has no endpoint.
 * There is no registry server and no Nacos client.
 * 这个端口在进程内。登记簿里没有端点时，用静态配置兜底。
 * 没有注册中心服务器，也没有 Nacos 客户端。
 */
package com.subjex.platform.contract.discovery;
