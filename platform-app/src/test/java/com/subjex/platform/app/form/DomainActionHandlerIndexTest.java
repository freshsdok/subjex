package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DomainActionHandlerIndexTest — indexing fail-closed: duplicate / missing keys rejected.
 * 索引失败关闭：重复或缺键时拒绝。
 */
class DomainActionHandlerIndexTest {

    @Test
    void duplicateKeyIsRejected() {
        DomainActionHandler first = stub(DomainActionKey.REGISTRY_REGISTER);
        DomainActionHandler second = stub(DomainActionKey.REGISTRY_REGISTER);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> FormDomainActionRunner.index(List.of(first, second)));
        assertTrue(ex.getMessage().contains("duplicate"));
        assertTrue(ex.getMessage().contains(DomainActionKey.REGISTRY_REGISTER.key()));
    }

    @Test
    void missingKeyIsRejected() {
        DomainActionHandler only = stub(DomainActionKey.REGISTRY_REGISTER);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> FormDomainActionRunner.index(List.of(only)));
        assertTrue(ex.getMessage().contains("missing"));
        assertTrue(ex.getMessage().contains(DomainActionKey.CONFIG_OVERRIDE.key()));
    }

    private static DomainActionHandler stub(DomainActionKey key) {
        return new DomainActionHandler() {
            @Override
            public DomainActionKey key() {
                return key;
            }

            @Override
            public String apply(
                    RenderedForm form,
                    Map<String, Object> accepted,
                    String tenantHeader,
                    OperatorPrincipal operator) {
                return "stub";
            }
        };
    }
}
