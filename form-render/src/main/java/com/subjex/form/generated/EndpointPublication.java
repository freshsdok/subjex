package com.subjex.form.generated;

/**
 * EndpointPublication — 端点发布：从表单 endpoint-publication 生成的记录，已检入仓库。
 * <p>
 * Endpoint publication. Checked in so the build does not run an annotation processor.
 * 检入仓库，构建时不需要注解处理器。
 */
public record EndpointPublication(String serviceName, String host, int port) {
}
