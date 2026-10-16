package com.subjex.platform.app.form;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.Map;

/**
 * DomainActionHandler — SPI for one {@link DomainActionKey} write path.
 * 领域动作处理器：单个 {@link DomainActionKey} 的写入 SPI。
 * <p>
 * Register as a Spring bean; {@link FormDomainActionRunner} indexes by {@link #key()}.
 * 注册为 Spring Bean；{@link FormDomainActionRunner} 按 {@link #key()} 建索引。
 */
public interface DomainActionHandler {

    /** Catalog key this handler owns — 本处理器负责的目录键。 */
    DomainActionKey key();

    /**
     * Apply the action; return a short effect summary string for the submission response.
     * 执行动作；返回提交响应用的短摘要字符串。
     */
    String apply(
            RenderedForm form, Map<String, Object> accepted, String tenantHeader, OperatorPrincipal operator);
}
