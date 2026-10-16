package com.subjex.platform.app.flags;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.config.ConfigCenterPort;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * FeatureFlagsEndpoint — thin MVP over config-center namespace {@code flags}.
 * Keys are {@code {tenantId}/{flagKey}} (tenant-scoped) or bare keys (platform-wide).
 * Boolean values: true/false/1/0/yes/no. Read: config.read; write: config.write.
 * Resolve at runtime via {@link FeatureFlagService#isEnabled}.
 * 薄 MVP；运行时经 FeatureFlagService.isEnabled 解析。
 */
@RestController
public class FeatureFlagsEndpoint {

    public static final String PATH = JsonApi.BASE + "/flags";

    private final ConfigCenterPort config;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public FeatureFlagsEndpoint(
            ConfigCenterPort config, TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        this.config = Objects.requireNonNull(config, "config");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    /** List flags; optional tenantId scopes to {@code tenantId/} prefix — 列开关；可选租户前缀。 */
    @GetMapping(PATH)
    public FlagsDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = optionalTenant(operator, tenantId);
        String prefix = tid == null ? null : tid + "/";
        List<FlagDocument> flags = new ArrayList<>();
        for (ConfigEntry row : config.list(ConfigNamespaces.FLAGS)) {
            String key = row.key();
            if (prefix != null && !key.startsWith(prefix)) {
                continue;
            }
            flags.add(toFlag(row, tid));
        }
        return new FlagsDocument(flags, ConfigNamespaces.FLAGS);
    }

    /** Get one flag by logical key (with optional tenantId) — 读单个逻辑键。 */
    @GetMapping(PATH + "/{flagKey}")
    public FlagDocument getOne(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("flagKey") String flagKey,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = optionalTenant(operator, tenantId);
        String storageKey = FeatureFlagService.storageKey(tid, flagKey);
        ConfigEntry row = config.get(ConfigNamespaces.FLAGS, storageKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return toFlag(row, tid);
    }

    /** Upsert flag string value (boolean-ish recommended) — 写入开关字符串值。 */
    @PutMapping(PATH + "/{flagKey}")
    public FlagDocument put(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("flagKey") String flagKey,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody FlagWriteRequest body) {
        if (body == null || body.value() == null) {
            throw new IllegalArgumentException("value required");
        }
        String tid = optionalTenant(operator, tenantId);
        String storageKey = FeatureFlagService.storageKey(tid, flagKey);
        ConfigEntry saved = config.put(ConfigNamespaces.FLAGS, storageKey, body.value().trim());
        return toFlag(saved, tid);
    }

    private String optionalTenant(OperatorPrincipal operator, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        String tid = tenantId.trim();
        tenantGuard.requireTenant(tid);
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static FlagDocument toFlag(ConfigEntry row, String tenantId) {
        Boolean enabled = FeatureFlagService.parseEnabled(row.value()).orElse(null);
        return new FlagDocument(
                row.key(),
                FeatureFlagService.logicalKey(row.key(), tenantId),
                row.value(),
                enabled,
                row.revision(),
                row.origin() == null ? null : row.origin().apiWord());
    }

    /** @deprecated use {@link FeatureFlagService#parseEnabled} — 请用 FeatureFlagService。 */
    static Boolean parseEnabled(String raw) {
        return FeatureFlagService.parseEnabled(raw).orElse(null);
    }

    public record FlagsDocument(List<FlagDocument> flags, String namespace) {}

    public record FlagDocument(
            String key,
            String flagKey,
            String value,
            Boolean enabled,
            long revision,
            String origin) {}

    public record FlagWriteRequest(String value) {}
}
