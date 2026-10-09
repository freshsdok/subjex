package com.subjex.platform.app.declaration;

import com.subjex.page.declare.PageRenderer;
import com.subjex.page.declare.RenderedFlow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DeclarationRuntimePages — 运行时页面绑定：从已晋升/生效的 flow YAML 派生 list/new/detail，不另写 page 声明。
 * <p>
 * RT-5: pages are <strong>not</strong> a separate declaration kind and are not materialized as
 * classpath YAML on promote. {@link com.subjex.platform.app.page.PagesApiEndpoint} already resolves
 * catalog + detail from {@link EffectiveDeclarationService#effectiveFlow} (DRAFT &gt; PROMOTED &gt;
 * classpath) when {@code X-Tenant-Id} is set. This helper (1) exposes the fixed console paths and
 * (2) fail-closes on <strong>flow</strong> promote when paths (and, for {@code tenantScoped}, the
 * four business-table blocks) do not match the product lock.
 * <p>
 * 页面不是独立声明种类，晋升时也不写 classpath page YAML。目录/详情已从生效 flow 读取。本助手暴露固定路径，
 * 并在流程晋升时校验路径（租户隔离业务表再校验四积木）。
 */
public final class DeclarationRuntimePages {

    /** Business-table four blocks (RT-2 wizard) — 业务表四积木。 */
    public static final String BLOCK_LIST_TABLE = "ListTable";

    public static final String BLOCK_FORM_FIELDS = "FormFields";

    public static final String BLOCK_DETAIL_READONLY = "DetailReadonly";

    public static final String BLOCK_SUBMIT_BAR = "SubmitBar";

    private static final Set<String> BUSINESS_TABLE_BLOCKS =
            Set.of(BLOCK_LIST_TABLE, BLOCK_FORM_FIELDS, BLOCK_DETAIL_READONLY, BLOCK_SUBMIT_BAR);

    private DeclarationRuntimePages() {}

    /**
     * Console paths + blocks derived from one rendered flow —
     * 从一份已渲染流程得到的控制台路径与积木。
     */
    public record Binding(
            String flowKey,
            String listPath,
            String newPath,
            String detailPath,
            String permission,
            boolean tenantScoped,
            List<String> listBlocks,
            List<String> detailBlocks,
            List<String> submitBlocks) {}

    /** Bind paths from a rendered flow — 从已渲染流程绑定路径。 */
    public static Binding fromFlow(RenderedFlow flow) {
        Objects.requireNonNull(flow, "flow");
        return new Binding(
                flow.flowKey(),
                flow.list().path(),
                flow.submit().path(),
                flow.detail().path(),
                flow.permission(),
                flow.tenantScoped(),
                flow.list().blocks(),
                flow.detail().blocks(),
                flow.submit().blocks());
    }

    /**
     * Render YAML then ensure promote-time page contract — 渲染 YAML 并校验晋升时页面契约。
     *
     * @return binding after validation / 校验后的绑定
     */
    public static Binding ensureAfterFlowPromote(String yamlBody) {
        RenderedFlow flow = new PageRenderer().render(yamlBody);
        return ensureAfterFlowPromote(flow);
    }

    /**
     * Fail-closed page contract on flow promote — 流程晋升时的页面契约（失败关闭）。
     * <ul>
     *   <li>Fixed paths: {@code /pages/{key}}, {@code /pages/{key}/new}, {@code /pages/{key}/{id}}</li>
     *   <li>When {@code tenantScoped}: all four blocks must appear across list/detail/submit</li>
     * </ul>
     */
    public static Binding ensureAfterFlowPromote(RenderedFlow flow) {
        Binding binding = fromFlow(flow);
        String key = binding.flowKey();
        String expectedList = "/pages/" + key;
        String expectedNew = "/pages/" + key + "/new";
        String expectedDetail = "/pages/" + key + "/{id}";
        if (!expectedList.equals(binding.listPath())) {
            throw new IllegalArgumentException(
                    "flow promote requires list.path " + expectedList + " (got " + binding.listPath() + ")");
        }
        if (!expectedNew.equals(binding.newPath())) {
            throw new IllegalArgumentException(
                    "flow promote requires submit.path " + expectedNew + " (got " + binding.newPath() + ")");
        }
        if (!expectedDetail.equals(binding.detailPath())) {
            throw new IllegalArgumentException(
                    "flow promote requires detail.path "
                            + expectedDetail
                            + " (got "
                            + binding.detailPath()
                            + ")");
        }
        if (binding.tenantScoped()) {
            Set<String> present = new LinkedHashSet<>();
            present.addAll(binding.listBlocks());
            present.addAll(binding.detailBlocks());
            present.addAll(binding.submitBlocks());
            if (!present.containsAll(BUSINESS_TABLE_BLOCKS)) {
                Set<String> missing = new LinkedHashSet<>(BUSINESS_TABLE_BLOCKS);
                missing.removeAll(present);
                throw new IllegalArgumentException(
                        "tenantScoped flow promote requires blocks "
                                + BUSINESS_TABLE_BLOCKS
                                + " across list/detail/submit (missing "
                                + missing
                                + ")");
            }
        }
        return binding;
    }
}
