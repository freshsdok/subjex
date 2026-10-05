package com.subjex.platform.contract.discovery;

/**
 * ServiceEndpoint — 服务端点：一个已命名服务的主机和端口。
 * <p>
 * This is an address, not a health report and not a route table.
 * 这是一个地址，不是健康报告，也不是路由表。
 */
public record ServiceEndpoint(String serviceName, String host, int port) {

    public ServiceEndpoint {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("service name is missing");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is missing");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port is out of range");
        }
    }
}
