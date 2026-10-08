package com.subjex.page.declare;

/**
 * RenderedFlow — 已渲染流程：流程键、标题、声明版本、权限与租户标志，以及列表 / 详情 / 提交三页描述。
 * <p>
 * {@code formKey} is optional; when set, the console submit page loads that form's fields.
 * {@code entityKey} is optional; when set, the flow is tied to an entity-declare key (list/detail APIs).
 * {@code version} is required (integer ≥ 1, fail-closed).
 * {@code permission} is required (fail-closed); {@code tenantScoped} defaults to false when omitted.
 * {@code formKey} 可选；有值时提交页按该表单字段渲染。
 * {@code entityKey} 可选；有值时流程绑定 entity-declare 键（列表/详情 API）。
 * {@code version} 必填（整数 ≥ 1，缺则拒绝）。
 * {@code permission} 必填（缺则拒绝）；{@code tenantScoped} 省略时默认为 false。
 */
public record RenderedFlow(
        String flowKey,
        String titleEn,
        String titleZh,
        String formKey,
        String entityKey,
        int version,
        String permission,
        boolean tenantScoped,
        ListPageSpec list,
        DetailPageSpec detail,
        SubmitPageSpec submit) {}
