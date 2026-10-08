package com.subjex.form.render;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DomainActionSpec — 领域动作规格：目录中一项的键、标题与必填/可选字段名。
 * <p>
 * Field names must match form field names (no remapping in this slice).
 * 字段名须与表单字段名一致（本切片不做重映射）。
 */
public record DomainActionSpec(
        DomainActionKey key,
        String titleEn,
        String titleZh,
        Set<String> requiredFields,
        Set<String> optionalFields) {

    public DomainActionSpec {
        requiredFields = Set.copyOf(requiredFields);
        optionalFields = Set.copyOf(optionalFields);
    }

    /** Every field name the action may read — 动作可读的全部字段名。 */
    public Set<String> allowedFields() {
        Set<String> allowed = new LinkedHashSet<>(requiredFields);
        allowed.addAll(optionalFields);
        return Set.copyOf(allowed);
    }
}
