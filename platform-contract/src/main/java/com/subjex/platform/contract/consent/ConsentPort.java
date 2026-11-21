package com.subjex.platform.contract.consent;

import java.util.List;
import java.util.Optional;

/**
 * ConsentPort — purpose-consent ledger: grant, withdraw, current, history, and fail-closed {@code require}.
 * 目的同意台账：授予、撤销、当前、历史，以及失败关闭的 {@code require}。
 * <p>
 * Tenant fail-closed; reserved {@code platform} is never a default; cross-tenant access is denied.
 * History is append-only (no updates or deletes of past events). Withdrawal is per purpose (the current grant).
 * {@code require}: if {@code requiredVersion} is present it must match the current grant's version exactly;
 * if absent, any current active grant for that purpose passes. The kernel stores no legal text.
 * 租户失败关闭；保留租户 {@code platform} 绝不是默认值；禁止跨租户访问。
 * 历史只追加（不改、不删旧事件）。撤销按目的作用于当前授予。
 * {@code require}：若给出 {@code requiredVersion} 则必须与当前授予版本完全一致；未给出则该目的上任意当前有效授予即可。
 * 内核不存放法律正文。
 */
public interface ConsentPort {

    /** Max length of a purpose key or consent-version id — 目的键或文本版本标识的最大长度. */
    int MAX_KEY_LENGTH = 64;

    /**
     * Allowed pattern: starts with a letter, then letters, digits, {@code .}, {@code _}, {@code :}, or {@code -}.
     * 允许格式：以字母开头，其后为字母、数字或 {@code .} {@code _} {@code :} {@code -}。
     */
    String KEY_PATTERN = "[A-Za-z][A-Za-z0-9._:-]{0,63}";

    /**
     * Record a grant for the purpose at the given tenant-supplied version, and return the new current record.
     * 记录对该目的、按租户提供版本的授予，并返回新的当前记录。
     */
    ConsentRecord grant(
            String tenantId, String subjectId, String purposeKey, String consentVersionId, String actorIdentityId);

    /**
     * Withdraw the current grant for the purpose (per-purpose granularity).
     * 撤销该目的上的当前授予（按目的粒度）。
     */
    ConsentEvent withdraw(String tenantId, String subjectId, String purposeKey, String actorIdentityId);

    /**
     * Current active grant for the purpose, if any.
     * 该目的上当前仍然有效的授予（若有）。
     */
    Optional<ConsentRecord> current(String tenantId, String subjectId, String purposeKey);

    /**
     * Current active grants for the subject in the tenant, purpose-sorted.
     * 该主体在该租户内全部当前有效授予，按目的键排序。
     */
    List<ConsentRecord> listCurrent(String tenantId, String subjectId);

    /**
     * Append-only history for the subject, oldest first. {@code purposeKey} may be null to return every purpose.
     * 追加式历史，从旧到新。{@code purposeKey} 为空则返回该主体全部目的。
     */
    List<ConsentEvent> history(String tenantId, String subjectId, String purposeKey);

    /**
     * Fail-closed check: a current grant must exist; when {@code requiredVersion} is given it must match exactly.
     * 失败关闭检查：必须有当前授予；若给出 {@code requiredVersion} 则必须完全一致。
     *
     * @throws ConsentRequiredException missing, withdrawn, or version mismatch / 缺失、已撤销或版本不匹配
     */
    void require(String tenantId, String subjectId, String purposeKey, String requiredVersion);
}
