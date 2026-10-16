package com.subjex.platform.app.form.action;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.form.DomainActionHandler;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.Map;
import java.util.Objects;

/**
 * Capability catalog invoke — one handler instance per capability domainAction key.
 * 能力目录调用 — 每个 capability domainAction 键一个处理器实例。
 * <p>
 * Not a {@code @Component}: registered as four beans in {@link DomainActionHandlerConfiguration}.
 * 非 {@code @Component}：在 {@link DomainActionHandlerConfiguration} 中注册四个 Bean。
 */
public final class CapabilityInvokeDomainActionHandler implements DomainActionHandler {

    private final DomainActionKey key;
    private final String capabilityId;
    private final CapabilityRunner capabilities;

    public CapabilityInvokeDomainActionHandler(
            DomainActionKey key, String capabilityId, CapabilityRunner capabilities) {
        this.key = Objects.requireNonNull(key, "key");
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public DomainActionKey key() {
        return key;
    }

    @Override
    public String apply(
            RenderedForm form, Map<String, Object> accepted, String tenantHeader, OperatorPrincipal operator) {
        return capabilities.run(capabilityId, accepted);
    }
}
