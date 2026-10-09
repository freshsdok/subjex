package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityDefinitionRejected;
import com.subjex.entity.declare.EntityFieldKind;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormDefinitionRejected;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Machine gates DECL-01 / DECL-02 for O6 zero-code refs.
 * O6 零代码引用门禁：subjectRef 只解析为主体；organizationRef 只解析为组织。
 */
class DeclarationRefGatesTest {

    private final EntityRenderer entities = new EntityRenderer();
    private final FormRenderer forms = new FormRenderer();

    @Test
    @DisplayName("DECL-01 subjectRef resolves Subject only")
    void DECL_01_subjectRefResolvesSubjectOnly() {
        RenderedEntity entity = entities.render("""
                entityKey: decl-subject
                tableName: decl_subject
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: assignee
                    kind: subjectRef
                    required: false
                """);
        assertEquals(EntityFieldKind.SUBJECT_REF, entity.fields().get(1).kind());
        assertEquals("subjectRef", entity.fields().get(1).kind().wireName());

        RenderedForm form = forms.render("""
                formKey: decl-subject
                titleEn: Decl subject
                titleZh: 主体引用
                version: 1
                permission: page.read
                domainAction: entity.record.upsert
                entityKey: decl-subject
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: assignee
                    kind: subjectRef
                    required: false
                """);
        assertEquals(FieldKind.SUBJECT_REF, form.fields().get(1).kind());
        assertEquals("subjectRef", form.fields().get(1).kind().wireName());

        // O8-5: legacy userRef/orgRef rejected — use subjectRef/organizationRef only
        EntityDefinitionRejected legacyUser =
                assertThrows(
                        EntityDefinitionRejected.class,
                        () -> entities.render("""
                                entityKey: decl-user-alias
                                tableName: decl_user_alias
                                version: 1
                                permission: page.read
                                fields:
                                  - name: id
                                    kind: text
                                    required: true
                                    maxLength: 8
                                  - name: owner
                                    kind: userRef
                                    required: false
                                """));
        assertTrue(legacyUser.getMessage().contains("subjectRef"));

        FormDefinitionRejected legacyOrg =
                assertThrows(
                        FormDefinitionRejected.class,
                        () -> forms.render("""
                                formKey: decl-org-alias
                                titleEn: Decl
                                titleZh: 别名
                                version: 1
                                permission: page.read
                                domainAction: entity.record.upsert
                                entityKey: decl-org-alias
                                fields:
                                  - name: id
                                    kind: text
                                    required: true
                                    maxLength: 8
                                  - name: dept
                                    kind: orgRef
                                    required: false
                                """));
        assertTrue(legacyOrg.getMessage().contains("organizationRef"));
    }

    @Test
    @DisplayName("DECL-02 organizationRef resolves Organization only")
    void DECL_02_organizationRefResolvesOrganizationOnly() {
        RenderedEntity entity = entities.render("""
                entityKey: decl-org
                tableName: decl_org
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: dept
                    kind: organizationRef
                    required: false
                """);
        assertEquals(EntityFieldKind.ORGANIZATION_REF, entity.fields().get(1).kind());
        assertEquals("organizationRef", entity.fields().get(1).kind().wireName());
        assertTrue(entity.fields().get(1).kind() != EntityFieldKind.SUBJECT_REF);

        RenderedForm form = forms.render("""
                formKey: decl-org
                titleEn: Decl org
                titleZh: 组织引用
                version: 1
                permission: page.read
                domainAction: entity.record.upsert
                entityKey: decl-org
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: dept
                    kind: organizationRef
                    required: false
                """);
        assertEquals(FieldKind.ORGANIZATION_REF, form.fields().get(1).kind());
        assertEquals("organizationRef", form.fields().get(1).kind().wireName());

        // Invented kinds must fail closed — 禁止虚构的组织单元引用种类
        EntityDefinitionRejected badEntity =
                assertThrows(
                        EntityDefinitionRejected.class,
                        () -> entities.render("""
                                entityKey: bad-unit
                                tableName: bad_unit
                                version: 1
                                permission: page.read
                                fields:
                                  - name: id
                                    kind: text
                                    required: true
                                    maxLength: 8
                                  - name: unit
                                    kind: organizationUnitRef
                                    required: false
                                """));
        assertTrue(badEntity.getMessage().contains("organizationRef"));

        FormDefinitionRejected badForm =
                assertThrows(
                        FormDefinitionRejected.class,
                        () -> forms.render("""
                                formKey: bad-unit
                                titleEn: Bad
                                titleZh: 坏
                                version: 1
                                permission: page.read
                                domainAction: entity.record.upsert
                                entityKey: bad-unit
                                fields:
                                  - name: id
                                    kind: text
                                    required: true
                                    maxLength: 8
                                  - name: unit
                                    kind: tenantOrgUnitRef
                                    required: false
                                """));
        assertTrue(badForm.getMessage().contains("organizationRef"));
    }
}
