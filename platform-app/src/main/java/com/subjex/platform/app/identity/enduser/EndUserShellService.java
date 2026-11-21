package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * EndUserShellService — WP7-1 profile / inbox / AUTH_END_USER questionnaire for {@link EndUserPrincipal}.
 * 终端外壳：资料、收件箱与认证问卷；不拓宽操作员路由。
 */
public final class EndUserShellService {

    /** Audit action for a successful display-name change — 展示名更新成功的审计动作. */
    public static final String PROFILE_UPDATE_ACTION = "end-user.profile.update";

    private static final int QUESTIONNAIRE_LIST_LIMIT = 200;

    private final EndUserAccountStore accounts;
    private final InboxPort inbox;
    private final QuestionnairePort questionnaires;
    private final AuditPort audit;
    private final Clock clock;

    public EndUserShellService(
            EndUserAccountStore accounts,
            InboxPort inbox,
            QuestionnairePort questionnaires,
            AuditPort audit,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.questionnaires = Objects.requireNonNull(questionnaires, "questionnaires");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Read own display name after tenant membership is confirmed.
     * 确认租户成员关系后读取本人展示名。
     */
    public EndUserRecord readProfile(EndUserPrincipal principal, String tenantId) {
        requireMembership(principal, tenantId);
        return requireRecord(principal);
    }

    /**
     * Update own {@code subjectName} and write an audit row for the acting identity.
     * 更新本人展示名，并为该身份写入审计行。
     */
    public EndUserRecord updateProfile(EndUserPrincipal principal, String tenantId, String subjectName) {
        Identity identity = requireMembership(principal, tenantId);
        EndUserRecord updated = accounts.updateDisplayName(principal.accountId(), subjectName);
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                identity.tenantId(),
                identity.identityId(),
                PROFILE_UPDATE_ACTION,
                principal.subjectId(),
                AuditOutcome.ALLOWED,
                clock.instant()));
        return updated;
    }

    /** List own END_USER mailbox in the current tenant — 列出当前租户下本人终端收件. */
    public List<InboxEntryView> listInbox(
            EndUserPrincipal principal,
            String tenantId,
            boolean unreadOnly,
            InboxChannel channel,
            int limit,
            int offset) {
        String tid = requireMembership(principal, tenantId).tenantId();
        return inbox.listEntries(
                tid,
                InboxRecipientRealm.END_USER,
                principal.subjectId(),
                new InboxListQuery(unreadOnly, channel, limit, offset));
    }

    /** Unread badge for own END_USER mailbox — 本人终端未读角标. */
    public long unreadCount(EndUserPrincipal principal, String tenantId) {
        String tid = requireMembership(principal, tenantId).tenantId();
        return inbox.unreadCount(tid, InboxRecipientRealm.END_USER, principal.subjectId());
    }

    /**
     * Own entry or 404 (another subject's message is invisible).
     * 本人收件，否则 404（他人邮件不可见）。
     */
    public InboxEntryView getInbox(EndUserPrincipal principal, String tenantId, String entryId) {
        String tid = requireMembership(principal, tenantId).tenantId();
        return inbox.getEntry(tid, InboxRecipientRealm.END_USER, principal.subjectId(), entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Mark one own entry read; missing / foreign → 404 — 标记本人已读；缺失或他人 → 404. */
    public void markRead(EndUserPrincipal principal, String tenantId, String entryId) {
        InboxEntryView own = getInbox(principal, tenantId, entryId);
        inbox.markRead(
                own.entry().tenantId(),
                InboxRecipientRealm.END_USER,
                principal.subjectId(),
                entryId,
                clock.instant());
    }

    /**
     * Published {@code AUTH_END_USER} questionnaires in the current business tenant only.
     * 仅当前业务租户已发布且采集模式为认证终端的问卷（无分配/ACL 产品）。
     */
    public List<QuestionnaireDefinition> listQuestionnaires(EndUserPrincipal principal, String tenantId) {
        String tid = requireMembership(principal, tenantId).tenantId();
        return questionnaires.list(tid, QUESTIONNAIRE_LIST_LIMIT).stream()
                .filter(EndUserShellService::visibleToEndUser)
                .toList();
    }

    /**
     * Fetch one visible questionnaire; others 404 (do not leak draft / ANON_LINK).
     * 读取一份对终端可见的问卷；其余 404，不泄露草稿或匿名问卷。
     */
    public QuestionnaireDefinition getQuestionnaire(
            EndUserPrincipal principal, String tenantId, String questionnaireId) {
        String tid = requireMembership(principal, tenantId).tenantId();
        return questionnaires
                .get(tid, questionnaireId)
                .filter(EndUserShellService::visibleToEndUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Submit as the end-user subject; reuse Q0–Q7 validation and one-submission rule.
     * 以终端主体提交；复用既有校验与“每人一份”规则。
     */
    public QuestionnaireResponse submitQuestionnaire(
            EndUserPrincipal principal,
            String tenantId,
            String questionnaireId,
            List<QuestionnaireAnswer> answers) {
        QuestionnaireDefinition definition = getQuestionnaire(principal, tenantId, questionnaireId);
        try {
            Optional<QuestionnaireResponse> submitted = questionnaires.submit(
                    definition.tenantId(),
                    definition.questionnaireId(),
                    QuestionnaireRespondentRealm.END_USER,
                    principal.subjectId(),
                    answers == null ? List.of() : answers);
            return submitted.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("already submitted")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
            }
            throw ex;
        }
    }

    private Identity requireMembership(EndUserPrincipal principal, String tenantId) {
        Objects.requireNonNull(principal, "principal");
        return accounts.requireActiveMembership(principal.accountId(), tenantId);
    }

    private EndUserRecord requireRecord(EndUserPrincipal principal) {
        return accounts
                .findByAccountId(principal.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static boolean visibleToEndUser(QuestionnaireDefinition definition) {
        return definition.status() == QuestionnaireStatus.PUBLISHED
                && definition.collectMode() == QuestionnaireCollectMode.AUTH_END_USER;
    }
}
