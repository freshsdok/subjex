package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.subjex.entity.generated.ServiceNote;
import com.subjex.entity.generated.ServiceNoteStore;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FormDomainActionRunnerTest — 领域动作执行测试：按声明键调用登记/配置/实体保存，不按 formKey。
 */
class FormDomainActionRunnerTest {

    private final ServiceCatalog services = mock(ServiceCatalog.class);
    private final ConfigCatalog config = mock(ConfigCatalog.class);
    private final ServiceNoteStore serviceNotes = mock(ServiceNoteStore.class);
    private final FormDomainActionRunner runner = new FormDomainActionRunner(services, config, serviceNotes);

    @Test
    void registersServiceForRegistryRegisterAction() {
        RenderedForm form = form("any-form-key", DomainActionKey.REGISTRY_REGISTER);
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
        RenderedForm form = form("another-key", DomainActionKey.CONFIG_OVERRIDE);
        String summary = runner.apply(form, Map.of("configKey", "subjex.greeting", "configValue", "hi"));
        assertEquals("subjex.greeting=hi", summary);
        verify(config).override("subjex.greeting", "hi");
    }

    @Test
    void savesServiceNoteForEntityAction() {
        RenderedForm form = form("service-note", DomainActionKey.ENTITY_SERVICE_NOTE_SAVE);
        String summary = runner.apply(
                form, Map.of("noteId", "n1", "title", "Hello", "body", "world", "priority", 3));
        assertEquals("n1:Hello", summary);
        ArgumentCaptor<ServiceNote> note = ArgumentCaptor.forClass(ServiceNote.class);
        verify(serviceNotes).save(note.capture());
        assertEquals("n1", note.getValue().noteId());
        assertEquals("Hello", note.getValue().title());
        assertEquals("world", note.getValue().body());
        assertEquals(3, note.getValue().priority());
    }

    private static RenderedForm form(String formKey, DomainActionKey action) {
        return new RenderedForm(
                formKey,
                "Demo",
                "演示",
                1,
                "page.read",
                false,
                action,
                "Demo",
                List.of(new FormField("serviceName", FieldKind.TEXT, true, null, null, 64)),
                List.of());
    }
}
