package com.subjex.platform.app.form.action;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.form.DomainActionHandler;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.config.ConfigNamespaces;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * config.override — write a config override via {@link ConfigCatalog}.
 * config.override — 经 ConfigCatalog 写配置覆盖。
 */
@Component
public final class ConfigOverrideDomainActionHandler implements DomainActionHandler {

    private final ConfigCatalog config;

    public ConfigOverrideDomainActionHandler(ConfigCatalog config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public DomainActionKey key() {
        return DomainActionKey.CONFIG_OVERRIDE;
    }

    @Override
    public String apply(
            RenderedForm form, Map<String, Object> accepted, String tenantHeader, OperatorPrincipal operator) {
        String configKey = Objects.toString(accepted.get("configKey"), "");
        String configValue = Objects.toString(accepted.get("configValue"), "");
        config.put(ConfigNamespaces.DEFAULT, configKey, configValue);
        return configKey + "=" + configValue;
    }
}
