package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.subjex.form.render.DeclaredEffect;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.form.render.SideEffectKey;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.extension.TaskDeliveryExtension;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskMessagePort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FormSideEffectRunnerTest — 表单副作用执行测试：声明的审计与任务经端口调用；审计载荷含版本关联；未知扩展拒绝。
 */
class FormSideEffectRunnerTest {

    private final OperatorActionAudit audit = mock(OperatorActionAudit.class);
    private final TaskMessagePort tasks = mock(TaskMessagePort.class);
    private final FormSideEffectRunner runner =
            new FormSideEffectRunner(audit, tasks, List.of(new TaskDeliveryExtension()));

    @Test
    void runsAuditAndTaskWhenDeclared() {
        RenderedForm form = formWithEffects(
                "demo-entity",
                new DeclaredEffect(
                        SideEffectKey.AUDIT_WRITE,
                        Map.of("actionName", "registry.register", "actionTargetField", "serviceName")),
                new DeclaredEffect(
                        SideEffectKey.TASK_ENQUEUE,
                        Map.of("stepName", "endpoint-published", "taskKind", "DETERMINISTIC")));
        OperatorPrincipal operator = operator();
        var outcomes =
                runner.run(form, Map.of("serviceName", "billing"), operator, "acme", EffectiveDeclarationService.SOURCE_PROMOTED);
        assertEquals(2, outcomes.size());
        assertEquals("audit.write", outcomes.get(0).key());
        assertEquals("ok", outcomes.get(0).outcome());
        assertEquals("task.enqueue", outcomes.get(1).key());

        verify(audit)
                .recordFormEffect(
                        operator,
                        "acme",
                        "registry.register",
                        "billing",
                        "demo-entity",
                        1,
                        EffectiveDeclarationService.SOURCE_PROMOTED,
                        AuditOutcome.ALLOWED);
        ArgumentCaptor<TaskCommand> command = ArgumentCaptor.forClass(TaskCommand.class);
        verify(tasks).submit(command.capture());
        TaskCommand submitted = command.getValue();
        assertEquals("acme", submitted.tenantId());
        assertEquals(TaskKind.DETERMINISTIC, submitted.taskKind());
        assertEquals("endpoint-published", submitted.stepName());
        assertEquals("identity-1", submitted.actorIdentityId());
    }

    @Test
    void auditPayloadIncludesTenantActorEntityAndRevision() {
        RenderedForm form = formWithEffects(
                "repair-ticket",
                new DeclaredEffect(
                        SideEffectKey.AUDIT_WRITE,
                        Map.of("actionName", "entity.record.upsert", "actionTargetField", "ticketId")));
        OperatorPrincipal operator = operator();
        runner.run(
                form,
                Map.of("ticketId", "T-1"),
                operator,
                "tenant-a",
                EffectiveDeclarationService.SOURCE_DRAFT);

        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entity = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> version = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(audit)
                .recordFormEffect(
                        org.mockito.ArgumentMatchers.eq(operator),
                        tenant.capture(),
                        action.capture(),
                        target.capture(),
                        entity.capture(),
                        version.capture(),
                        source.capture(),
                        org.mockito.ArgumentMatchers.eq(AuditOutcome.ALLOWED));
        assertEquals("tenant-a", tenant.getValue());
        assertEquals("entity.record.upsert", action.getValue());
        assertEquals("T-1", target.getValue());
        assertEquals("repair-ticket", entity.getValue());
        assertEquals(1, version.getValue());
        assertEquals(EffectiveDeclarationService.SOURCE_DRAFT, source.getValue());
        assertEquals("identity-1", operator.identityId());
    }

    @Test
    void invokesKnownExtension() {
        RenderedForm form = formWithEffects(
                null,
                new DeclaredEffect(SideEffectKey.EXTENSION_INVOKE, Map.of("extensionName", "task-delivery")));
        runner.run(form, Map.of(), operator(), null, EffectiveDeclarationService.SOURCE_CLASSPATH);
        verifyNoInteractions(audit);
        verifyNoInteractions(tasks);
    }

    @Test
    void unknownExtensionIsRejected() {
        RenderedForm form = formWithEffects(
                null,
                new DeclaredEffect(SideEffectKey.EXTENSION_INVOKE, Map.of("extensionName", "no-such-extension")));
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(form, Map.of(), operator(), null, EffectiveDeclarationService.SOURCE_CLASSPATH));
    }

    private static RenderedForm formWithEffects(String entityKey, DeclaredEffect... effects) {
        return new RenderedForm(
                "demo",
                "Demo",
                "演示",
                1,
                "registry.write",
                false,
                DomainActionKey.REGISTRY_REGISTER,
                entityKey,
                "Demo",
                List.of(new FormField("serviceName", FieldKind.TEXT, true, null, null, 64, List.of())),
                List.of(effects));
    }

    private static OperatorPrincipal operator() {
        return new OperatorPrincipal("platform-operator", "hash", "identity-1", "subject-1", Set.of(), true);
    }
}
