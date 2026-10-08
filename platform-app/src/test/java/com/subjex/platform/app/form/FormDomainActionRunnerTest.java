package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FormDomainActionRunnerTest — 领域动作执行测试：按声明键调用登记/配置/实体保存，不按 formKey。
 */
class FormDomainActionRunnerTest {

    private final ServiceCatalog services = mock(ServiceCatalog.class);
    private final ConfigCatalog config = mock(ConfigCatalog.class);
    private final EntityCatalog entities = EntityCatalog.load(EntityCatalog.class.getClassLoader());
    private final EffectiveDeclarationService effective = mock(EffectiveDeclarationService.class);
    private final GenericEntityStore genericEntities = mock(GenericEntityStore.class);
    private final FormDomainActionRunner runner =
            new FormDomainActionRunner(
                    services, config, effective, genericEntities,
                    new CapabilityRunner(new CapabilityCatalog()));

    @BeforeEach
    void stubRuntimeEntity() {
        when(effective.runtimeEntity(any(), eq("demo-ticket"))).thenAnswer(inv -> entities.find("demo-ticket"));
        when(effective.runtimeEntity(any(), eq("service-note"))).thenAnswer(inv -> entities.find("service-note"));
        when(effective.runtimeEntity(any(), eq("no-such-entity"))).thenReturn(Optional.empty());
    }

    @Test
    void registersServiceForRegistryRegisterAction() {
        RenderedForm form = form("any-form-key", DomainActionKey.REGISTRY_REGISTER, null);
        String summary = runner.apply(
                form, Map.of("serviceName", "billing", "host", "10.0.0.1", "port", 8080));
        assertEquals("billing@10.0.0.1:8080", summary);
        ArgumentCaptor<ServiceEndpoint> endpoint = ArgumentCaptor.forClass(ServiceEndpoint.class);
        verify(services).register(endpoint.capture());
        assertEquals("billing", endpoint.getValue().serviceName());
        assertEquals("10.0.0.1", endpoint.getValue().host());
        assertEquals(8080, endpoint.getValue().port());
    }

    @Test
    void overridesConfigForConfigOverrideAction() {
        RenderedForm form = form("another-key", DomainActionKey.CONFIG_OVERRIDE, null);
        String summary = runner.apply(form, Map.of("configKey", "subjex.greeting", "configValue", "hi"));
        assertEquals("subjex.greeting=hi", summary);
        verify(config).override("subjex.greeting", "hi");
    }

    @Test
    void upsertsServiceNoteViaGenericStore() {
        RenderedForm form = form("service-note", DomainActionKey.ENTITY_RECORD_UPSERT, "service-note");
        String summary = runner.apply(
                form, Map.of("noteId", "n1", "title", "Hello", "body", "world", "priority", 3));
        assertEquals("service-note:n1", summary);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RenderedEntity> entity = ArgumentCaptor.forClass(RenderedEntity.class);
        verify(genericEntities).save(entity.capture(), values.capture());
        assertEquals("service-note", entity.getValue().entityKey());
        assertEquals("n1", values.getValue().get("noteId"));
        assertEquals("Hello", values.getValue().get("title"));
        assertEquals("world", values.getValue().get("body"));
        assertEquals(3, values.getValue().get("priority"));
    }

    @Test
    void upsertsGenericEntityForRecordUpsertAction() {
        RenderedForm form = form("demo-ticket", DomainActionKey.ENTITY_RECORD_UPSERT, "demo-ticket");
        String summary = runner.apply(
                form,
                Map.of(
                        "ticketId",
                        "t1",
                        "title",
                        "Open",
                        "status",
                        "new",
                        "assignee",
                        "u1",
                        "extraIgnored",
                        "x"));
        assertEquals("demo-ticket:t1", summary);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RenderedEntity> entity = ArgumentCaptor.forClass(RenderedEntity.class);
        verify(genericEntities).save(entity.capture(), values.capture());
        assertEquals("demo-ticket", entity.getValue().entityKey());
        assertEquals("t1", values.getValue().get("ticketId"));
        assertEquals("Open", values.getValue().get("title"));
        assertEquals("new", values.getValue().get("status"));
        assertEquals("u1", values.getValue().get("assignee"));
        assertEquals(false, values.getValue().containsKey("extraIgnored"));
    }

    @Test
    void upsertRejectsUnknownEntityKey() {
        RenderedForm form = form("demo-ticket", DomainActionKey.ENTITY_RECORD_UPSERT, "no-such-entity");
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.apply(form, Map.of("ticketId", "t1", "title", "x", "status", "new")));
    }


    @Test
    void runsAlgoHashFingerprintCapability() {
        RenderedForm form = form("any", DomainActionKey.CAPABILITY_ALGO_HASH_FINGERPRINT, null);
        String summary = runner.apply(form, Map.of("inputText", "abc"));
        assertEquals(64, summary.length());
        assertEquals(summary, runner.apply(form, Map.of("inputText", "abc")));
    }

    @Test
    void runsAiSummarizePreviewWithoutStoreMutation() {
        RenderedForm form = form("any", DomainActionKey.CAPABILITY_AI_SUMMARIZE_PREVIEW, null);
        String summary = runner.apply(form, Map.of("inputText", "ticket body"));
        assertTrue(summary.contains("model-gateway stub preview"));
        assertTrue(summary.contains("ticket body"));
        verify(genericEntities, never()).save(any(), any());
    }

    private static RenderedForm form(String formKey, DomainActionKey action, String entityKey) {
        return new RenderedForm(
                formKey,
                "Demo",
                "演示",
                1,
                "page.read",
                false,
                action,
                entityKey,
                "Demo",
                List.of(new FormField("serviceName", FieldKind.TEXT, true, null, null, 64, List.of())),
                List.of());
    }
}
