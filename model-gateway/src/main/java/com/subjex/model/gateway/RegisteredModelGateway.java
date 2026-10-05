package com.subjex.model.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RegisteredModelGateway — 已登记的模型网关：先登记提供者，再记下一次调用。
 * <p>
 * The registry and the invocation stay in this object. platform-app does not load this class.
 * 登记簿和这次调用都留在这个对象里。platform-app 不装载这个类。
 */
public final class RegisteredModelGateway {

    private final Map<String, String> modelIdByProviderId = new LinkedHashMap<>();
    private ModelInvocation invocation;

    /**
     * Register who serves which model — 登记谁提供哪一个模型。
     */
    public synchronized void register(String providerId, String modelId) {
        require(providerId, "provider id");
        require(modelId, "model id");
        modelIdByProviderId.put(providerId, modelId);
    }

    /**
     * Record one invocation of a registered provider — 记下已登记提供者的一次调用。
     */
    public synchronized ModelInvocation record(String providerId, String modelId, String inputDigest) {
        require(providerId, "provider id");
        require(modelId, "model id");
        require(inputDigest, "input digest");
        String registered = modelIdByProviderId.get(providerId);
        if (registered == null || !registered.equals(modelId)) {
            throw new IllegalArgumentException("model provider is not registered");
        }
        invocation = new ModelInvocation(providerId, modelId, inputDigest);
        return invocation;
    }

    /**
     * @return the invocation this gateway recorded, if any / 这个网关记下的那一次调用
     */
    public synchronized Optional<ModelInvocation> invocation() {
        return Optional.ofNullable(invocation);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is missing");
        }
    }
}
