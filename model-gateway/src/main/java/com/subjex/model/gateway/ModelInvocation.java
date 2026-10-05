package com.subjex.model.gateway;

/**
 * ModelInvocation — 一次模型调用：提供者标识、模型标识、输入摘要。
 * <p>
 * The gateway records these three facts. It does not call a vendor and it does not store them in a database.
 * 网关记下这三件事实。它不调用厂商，也不把它们写入数据库。
 */
public record ModelInvocation(String providerId, String modelId, String inputDigest) {

    public ModelInvocation {
        require(providerId, "provider id");
        require(modelId, "model id");
        require(inputDigest, "input digest");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is missing");
        }
    }
}
