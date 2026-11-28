package com.subjex.platform.app.schedule;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.schedule.SchedulePort;
import com.subjex.platform.contract.task.TaskMessagePort;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ScheduleWorkerSpringCtorTest — Spring must pick the production constructor when a test constructor also exists.
 * Spring 在同时存在测试构造时必须选用生产构造，否则会去找无参构造并启动失败（VendorStartupTest 根因）。
 */
class ScheduleWorkerSpringCtorTest {

    @Test
    void springInstantiatesScheduleWorkerWithoutADefaultConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SchedulePort.class, () -> Mockito.mock(SchedulePort.class));
            context.registerBean(TaskMessagePort.class, () -> Mockito.mock(TaskMessagePort.class));
            context.registerBean(WorkflowService.class, () -> Mockito.mock(WorkflowService.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(TransactionTemplate.class, () -> new TransactionTemplate(noopTransactions()));
            context.register(ScheduleWorker.class);
            context.refresh();
            assertNotNull(context.getBean(ScheduleWorker.class));
        }
    }

    private static PlatformTransactionManager noopTransactions() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() throws TransactionException {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
                // no-op — 空操作
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
                // no-op — 空操作
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
                // no-op — 空操作
            }
        };
    }
}
