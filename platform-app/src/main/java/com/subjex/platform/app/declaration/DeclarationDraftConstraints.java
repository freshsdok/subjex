package com.subjex.platform.app.declaration;

import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DeclaredEffect;
import com.subjex.form.render.RenderedForm;
import com.subjex.form.render.SideEffectKey;
import com.subjex.page.declare.RenderedFlow;
import com.subjex.platform.app.security.OperatorPermission;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * DeclarationDraftConstraints — 租户草稿保存/晋升约束：权限须来自平台目录；表单副作用仅白名单。
 * <p>
 * RT-3: {@code permission} must match a known {@link OperatorPermission} name (pick-only; no invent).
 * Form {@code effects} on tenant drafts may only be {@code audit.write} or {@code task.enqueue}
 * ({@link SideEffectKey#allowedOnTenantDraft()}); {@code extension.invoke} stays classpath-only.
 * 权限必须是已知平台权限名（只选不造）；租户草稿表单副作用仅审计与任务入队；扩展调用仍限 classpath。
 */
public final class DeclarationDraftConstraints {

    private DeclarationDraftConstraints() {}

    /** Known platform permission names — 已知平台权限名。 */
    public static Set<String> knownPermissionNames() {
        Set<String> names = new LinkedHashSet<>();
        for (OperatorPermission permission : OperatorPermission.values()) {
            names.add(permission.permissionName());
        }
        return Set.copyOf(names);
    }

    /** Whether the name is in the platform catalog — 是否在平台权限目录中。 */
    public static boolean isKnownPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        String trimmed = permission.trim();
        for (OperatorPermission known : OperatorPermission.values()) {
            if (known.permissionName().equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refuse unknown permission (400 via IllegalArgumentException) —
     * 未知权限拒绝（经 IllegalArgumentException → 400）。
     */
    public static void requireKnownPermission(String permission) {
        if (!isKnownPermission(permission)) {
            throw new IllegalArgumentException(
                    "permission must be picked from the platform catalog (unknown: "
                            + (permission == null ? "" : permission.trim())
                            + ")");
        }
    }

    /** Entity draft / promote permission gate — 实体草稿/晋升权限门闩。 */
    public static void requireEntity(RenderedEntity entity) {
        Objects.requireNonNull(entity, "entity");
        requireKnownPermission(entity.permission());
    }

    /** Form draft / promote: permission + effect whitelist — 表单：权限 + 副作用白名单。 */
    public static void requireForm(RenderedForm form) {
        Objects.requireNonNull(form, "form");
        requireKnownPermission(form.permission());
        for (DeclaredEffect effect : form.effects()) {
            requireFormEffectWhitelisted(effect.key());
        }
    }

    /** Flow draft / promote permission gate — 流程草稿/晋升权限门闩。 */
    public static void requireFlow(RenderedFlow flow) {
        Objects.requireNonNull(flow, "flow");
        requireKnownPermission(flow.permission());
    }

    /**
     * Tenant-draft form effects: only audit.write | task.enqueue —
     * 租户草稿表单副作用仅 audit.write | task.enqueue。
     */
    public static void requireFormEffectWhitelisted(SideEffectKey key) {
        Objects.requireNonNull(key, "key");
        if (!key.allowedOnTenantDraft()) {
            throw new IllegalArgumentException(
                    "form effect not allowed on tenant draft (whitelist: audit.write, task.enqueue; got "
                            + key.key()
                            + ")");
        }
    }
}
