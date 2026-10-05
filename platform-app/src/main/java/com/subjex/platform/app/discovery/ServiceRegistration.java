package com.subjex.platform.app.discovery;

/**
 * ServiceRegistration — 一次登记：调用方报上的服务名、主机和端口。
 * <p>
 * It does not carry a health score. The list computes the status word itself.
 * 它不带健康分。名单自己计算状态词。
 */
public record ServiceRegistration(String serviceName, String host, int port) {}
