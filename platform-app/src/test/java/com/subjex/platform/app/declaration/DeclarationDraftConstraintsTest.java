package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.EntityFieldKind;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DeclaredEffect;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import com.subjex.form.render.SideEffectKey;
import com.subjex.page.declare.PageRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;


/**
 * DeclarationDraftConstraintsTest — purpose: RT-3 draft save gates (permission catalog + form effect whitelist).
 * Gates: unknown permission reject; effect outside audit.write|task.enqueue reject (fail-closed).
 * <p>
 * 目的：RT-3 草稿保存门禁（权限目录 + 表单副作用白名单）。门禁：未知权限拒绝；副作用越白名单失败关闭。
 */
class DeclarationDraftConstraintsTest {

    @Test
    void knownPermissionsIncludePageRead() {
        assertTrue(DeclarationDraftConstraints.isKnownPermission("page.read"));
        assertTrue(DeclarationDraftConstraints.knownPermissionNames().contains("declaration.write"));
        assertFalse(DeclarationDraftConstraints.isKnownPermission("invented.perm"));
        assertFalse(DeclarationDraftConstraints.isKnownPermission(""));
    }

    @Test
    void unknownPermissionIsRejected() {
        RenderedEntity entity = new RenderedEntity(
                "x",
                "x",
                1,
                "not.a.real.permission",
                false,
                EntityStorageMode.TABLE,
                "X",
                List.of(new EntityField("id", EntityFieldKind.TEXT, true, 64)));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DeclarationDraftConstraints.requireEntity(entity));
        assertTrue(ex.getMessage().contains("platform catalog"));
    }

    @Test
    void formRejectsExtensionInvokeOnTenantDraft() {
        RenderedForm form = new RenderedForm(
                "demo",
                "Demo",
                "演示",
                1,
                "page.read",
                true,
                DomainActionKey.ENTITY_RECORD_UPSERT,
                "demo",
                "Demo",
                List.of(new FormField("id", FieldKind.TEXT, true, null, null, 64, List.of())),
                List.of(new DeclaredEffect(SideEffectKey.EXTENSION_INVOKE, Map.of("extensionName", "task-delivery"))));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DeclarationDraftConstraints.requireForm(form));
        assertTrue(ex.getMessage().contains("whitelist"));
        assertTrue(ex.getMessage().contains("extension.invoke"));
    }

    @Test
    void formAllowsAuditAndTaskEnqueue() {
        RenderedForm form = new RenderedForm(
                "demo",
                "Demo",
                "演示",
                1,
                "page.read",
                true,
                DomainActionKey.ENTITY_RECORD_UPSERT,
                "demo",
                "Demo",
                List.of(new FormField("id", FieldKind.TEXT, true, null, null, 64, List.of())),
                List.of(
                        new DeclaredEffect(
                                SideEffectKey.AUDIT_WRITE,
                                Map.of("actionName", "entity.record.upsert", "actionTargetField", "id")),
                        new DeclaredEffect(
                                SideEffectKey.TASK_ENQUEUE,
                                Map.of("stepName", "after-submit", "taskKind", "DETERMINISTIC"))));
        assertDoesNotThrow(() -> DeclarationDraftConstraints.requireForm(form));
    }

    @Test
    void renderThenRequireAcceptsClasspathStyleWhitelistEffects() {
        String yaml =
                """
                formKey: demo
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                tenantScoped: true
                domainAction: entity.record.upsert
                entityKey: demo
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 64
                effects:
                  - key: audit.write
                    params:
                      actionName: entity.record.upsert
                      actionTargetField: id
                """;
        RenderedForm form = new FormRenderer().render(yaml);
        assertDoesNotThrow(() -> DeclarationDraftConstraints.requireForm(form));
    }

    @Test
    void flowUnknownPermissionRejected() {
        String yaml =
                """
                flowKey: demo
                titleEn: Demo
                titleZh: 演示
                formKey: demo
                entityKey: demo
                version: 1
                permission: invented.perm
                tenantScoped: true
                list:
                  path: /pages/demo
                  apiPath: /api/v1/entities/demo/records
                  itemsKey: records
                  blocks:
                    - ListTable
                detail:
                  path: /pages/demo/{id}
                  apiPath: /api/v1/entities/demo/records
                  itemsKey: records
                  idField: id
                  blocks:
                    - DetailReadonly
                submit:
                  path: /pages/demo/new
                  apiPath: /api/v1/forms/demo/submissions
                  redirectTo: /pages/demo
                  blocks:
                    - FormFields
                    - SubmitBar
                """;
        var flow = new PageRenderer().render(yaml);
        assertThrows(IllegalArgumentException.class, () -> DeclarationDraftConstraints.requireFlow(flow));
    }
}
