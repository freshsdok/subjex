/**
 * Legacy org compatibility (O8-3 / O8-4, completed in O8 FULL PASS) — thin adapters over the
 * Organization ontology. New API / Policy / zero-code / declaration code must <em>not</em> depend
 * on this package (Legacy → New only; never New → Legacy).
 * <p>
 * Contains deprecated {@code /api/v1/org/**}, {@link com.subjex.platform.app.org.legacy.JdbcOrgDirectory},
 * {@link com.subjex.platform.app.org.legacy.OrgUnit} / {@link com.subjex.platform.app.org.legacy.OrgMembership}
 * projections, and deprecated {@link com.subjex.platform.app.org.legacy.OrgScope}.
 * Directory reads are ontology-first (no map joins). Prefer {@code /api/v1/organizations}.
 * <p>
 * 旧组织兼容层（O8-3/O8-4，已随 O8 全量通过完成隔离）：薄适配组织本体。新 API/策略/零代码/声明
 * <strong>不得</strong>依赖本包（只允许 Legacy→New）。含弃用的 {@code /api/v1/org/**} 与投影类型；
 * 请改用 {@code /api/v1/organizations}。
 */
package com.subjex.platform.app.org.legacy;
