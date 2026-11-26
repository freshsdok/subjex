package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxAttachment;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireStatus;
import com.subjex.platform.app.storage.IssuedDownloadLink;
import com.subjex.platform.app.storage.ObjectContentTypes;
import com.subjex.platform.app.storage.ObjectDescriptor;
import com.subjex.platform.app.storage.ObjectDownloadDeniedException;
import com.subjex.platform.app.storage.ObjectDownloadLinkService;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * EndUserShellService — profile / inbox / AUTH_END_USER questionnaire for {@link EndUserPrincipal}.
 * 终端外壳：资料（含头像与须验证邮箱）、收件箱与认证问卷；不拓宽操作员路由。
 * Inbox attachments are listed and downloaded only for the caller's own entry (ADR 0009),
 * as raw bytes — not ObjectGrantStore signed links. No end-user compose/send.
 * 附件仅限本人收件条目列表与直接下字节，不签发授权对象链接；不做终端写信。
 */
public final class EndUserShellService {

    /** Audit action for a successful display-name change — 展示名更新成功的审计动作. */
    public static final String PROFILE_UPDATE_ACTION = "end-user.profile.update";

    /** Audit action for setting or clearing the avatar — 设置或清除头像的审计动作. */
    public static final String PROFILE_AVATAR_ACTION = "end-user.profile.avatar.update";

    /** Audit action for a profile-email verification-code send — 资料邮箱验证码发送的审计动作. */
    public static final String PROFILE_EMAIL_CODE_ACTION = "end-user.profile.email.code";

    /** Audit action for promoting a pending email after a valid code — 验证码通过后提升待验证邮箱. */
    public static final String PROFILE_EMAIL_VERIFY_ACTION = "end-user.profile.email.verify";

    private static final int QUESTIONNAIRE_LIST_LIMIT = 200;

    private final EndUserAccountStore accounts;
    private final InboxPort inbox;
    private final QuestionnairePort questionnaires;
    private final AuditPort audit;
    private final ObjectStorage objects;
    private final EndUserProfileStore profiles;
    private final ObjectGrantStore grants;
    private final ObjectDownloadLinkService downloads;
    private final VerificationCodePort codes;
    private final Clock clock;

    public EndUserShellService(
            EndUserAccountStore accounts,
            InboxPort inbox,
            QuestionnairePort questionnaires,
            AuditPort audit,
            ObjectStorage objects,
            EndUserProfileStore profiles,
            ObjectGrantStore grants,
            ObjectDownloadLinkService downloads,
            VerificationCodePort codes,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.questionnaires = Objects.requireNonNull(questionnaires, "questionnaires");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Read own profile after tenant membership is confirmed.
     * 确认租户成员关系后读取本人资料。
     */
    public ProfileView readProfile(EndUserPrincipal principal, String tenantId) {
        requireMembership(principal, tenantId);
        return view(requireRecord(principal));
    }

    /**
     * Update own {@code subjectName} and/or set the avatar to an owned image object.
     * 更新本人展示名，和/或把头像设为本人拥有的图片对象。
     * {@code setAvatar} distinguishes "leave unchanged" from "clear / replace".
     * {@code setAvatar} 用于区分「保持不变」与「清除或更换」。
     * An email in the request body is ignored — email becomes the profile address
     * only after {@link #verifyEmail}.
     * 请求体中的邮箱字段会被忽略；只有验证通过后才成为资料邮箱。
     */
    public ProfileView updateProfile(
            EndUserPrincipal principal,
            String tenantId,
            String subjectName,
            String avatarObjectId,
            boolean setAvatar) {
        Identity identity = requireMembership(principal, tenantId);
        EndUserRecord record = requireRecord(principal);
        if (subjectName != null) {
            record = accounts.updateDisplayName(principal.accountId(), subjectName);
            audit.record(new AuditEntry(
                    UUID.randomUUID().toString(),
                    identity.tenantId(),
                    identity.identityId(),
                    PROFILE_UPDATE_ACTION,
                    principal.subjectId(),
                    AuditOutcome.ALLOWED,
                    clock.instant()));
        }
        if (setAvatar) {
            applyAvatar(principal, identity, avatarObjectId);
        }
        return view(record);
    }

    /**
     * Clear the avatar reference. Does not delete the underlying object.
     * 清除头像引用，不删除底层对象。
     */
    public ProfileView clearAvatar(EndUserPrincipal principal, String tenantId) {
        Identity identity = requireMembership(principal, tenantId);
        profiles.setAvatar(principal.accountId(), null, null);
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                identity.tenantId(),
                identity.identityId(),
                PROFILE_AVATAR_ACTION,
                principal.subjectId(),
                AuditOutcome.ALLOWED,
                clock.instant()));
        return view(requireRecord(principal));
    }

    /**
     * Store the address as pending and send a verification code. Does not change a verified email.
     * 把地址存为待验证并发送验证码，不改动已验证邮箱。
     */
    public VerificationCodePort.IssuedCode requestEmailCode(
            EndUserPrincipal principal, String tenantId, String email) {
        Identity identity = requireMembership(principal, tenantId);
        String address = EndUserProfileStore.requireEmail(email);
        EndUserProfile current = profiles.requireOrEmpty(principal.accountId());
        if (address.equals(current.email())) {
            throw new IllegalArgumentException("email is already verified");
        }
        try {
            VerificationCodePort.IssuedCode issued = codes.issue(address, VerificationCodePort.PURPOSE_EMAIL_VERIFY);
            profiles.setPendingEmail(principal.accountId(), address, clock.instant());
            audit.record(new AuditEntry(
                    UUID.randomUUID().toString(),
                    identity.tenantId(),
                    identity.identityId(),
                    PROFILE_EMAIL_CODE_ACTION,
                    VerificationCodePort.PURPOSE_EMAIL_VERIFY
                            + ":"
                            + EndUserPhoneAuthService.maskDestination(address),
                    AuditOutcome.ALLOWED,
                    clock.instant()));
            return issued;
        } catch (VerificationCodeSenderUnconfiguredException ex) {
            audit.record(new AuditEntry(
                    UUID.randomUUID().toString(),
                    identity.tenantId(),
                    identity.identityId(),
                    PROFILE_EMAIL_CODE_ACTION,
                    VerificationCodePort.PURPOSE_EMAIL_VERIFY
                            + ":"
                            + EndUserPhoneAuthService.maskDestination(address),
                    AuditOutcome.REFUSED,
                    clock.instant()));
            throw ex;
        } catch (VerificationCodeSendFailedException ex) {
            audit.record(new AuditEntry(
                    UUID.randomUUID().toString(),
                    identity.tenantId(),
                    identity.identityId(),
                    PROFILE_EMAIL_CODE_ACTION,
                    VerificationCodePort.PURPOSE_EMAIL_VERIFY
                            + ":"
                            + EndUserPhoneAuthService.maskDestination(address),
                    AuditOutcome.FAILED,
                    clock.instant()));
            throw ex;
        }
    }

    /**
     * Consume a live code and promote the pending address to the verified profile email.
     * 消费仍有效的验证码，把待验证地址提升为资料邮箱。
     */
    public ProfileView verifyEmail(EndUserPrincipal principal, String tenantId, String email, String code) {
        Identity identity = requireMembership(principal, tenantId);
        String address = EndUserProfileStore.requireEmail(email);
        if (!codes.verifyAndConsume(address, VerificationCodePort.PURPOSE_EMAIL_VERIFY, code)) {
            throw new InvalidVerificationCodeException();
        }
        EndUserProfile confirmed = profiles.confirmPendingEmail(principal.accountId(), address, clock.instant());
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                identity.tenantId(),
                identity.identityId(),
                PROFILE_EMAIL_VERIFY_ACTION,
                EndUserPhoneAuthService.maskDestination(confirmed.email()),
                AuditOutcome.ALLOWED,
                clock.instant()));
        return view(requireRecord(principal));
    }

    private void applyAvatar(EndUserPrincipal principal, Identity identity, String avatarObjectId) {
        if (avatarObjectId == null || avatarObjectId.isBlank()) {
            profiles.setAvatar(principal.accountId(), null, null);
            audit.record(new AuditEntry(
                    UUID.randomUUID().toString(),
                    identity.tenantId(),
                    identity.identityId(),
                    PROFILE_AVATAR_ACTION,
                    principal.subjectId(),
                    AuditOutcome.ALLOWED,
                    clock.instant()));
            return;
        }
        ObjectDescriptor descriptor = grants
                .findByObjectId(avatarObjectId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "avatar object not found"));
        if (!identity.tenantId().equals(descriptor.tenantId())
                || !principal.subjectId().equals(descriptor.ownerSubjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "avatar object not found");
        }
        String mediaType = objects
                .find(descriptor.tenantId(), descriptor.objectKey())
                .map(StoredObject::mediaType)
                .orElse(null);
        if (!ObjectContentTypes.isImage(mediaType)) {
            throw new IllegalArgumentException("avatar must be an image");
        }
        profiles.setAvatar(principal.accountId(), descriptor.objectId(), descriptor.tenantId());
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                identity.tenantId(),
                identity.identityId(),
                PROFILE_AVATAR_ACTION,
                descriptor.objectId(),
                AuditOutcome.ALLOWED,
                clock.instant()));
    }

    private ProfileView view(EndUserRecord record) {
        EndUserProfile profile = profiles.requireOrEmpty(record.account().accountId());
        return new ProfileView(record, profile, issueAvatarDownload(record.subject().subjectId(), profile));
    }

    private IssuedDownloadLink issueAvatarDownload(String actorSubjectId, EndUserProfile profile) {
        if (profile.avatarObjectId() == null
                || profile.avatarObjectId().isBlank()
                || profile.avatarTenantId() == null
                || profile.avatarTenantId().isBlank()) {
            return null;
        }
        ObjectDescriptor descriptor = grants.findByObjectId(profile.avatarObjectId()).orElse(null);
        if (descriptor == null || !profile.avatarTenantId().equals(descriptor.tenantId())) {
            return null;
        }
        try {
            return downloads.issue(descriptor.tenantId(), descriptor.objectKey(), actorSubjectId, null);
        } catch (ObjectDownloadDeniedException ex) {
            return null;
        }
    }

    /** Assembled profile for the API — 组装后的资料视图. */
    public record ProfileView(EndUserRecord record, EndUserProfile profile, IssuedDownloadLink avatarDownload) {
        public ProfileView {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(profile, "profile");
        }
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
     * List attachments on an own inbox entry. Foreign or missing entry → 404.
     * 列出本人收件上的附件；他人或缺失条目为 404。
     */
    public List<InboxAttachment> listAttachments(EndUserPrincipal principal, String tenantId, String entryId) {
        InboxEntryView own = getInbox(principal, tenantId, entryId);
        return inbox.listAttachmentsForOwnEntry(
                own.entry().tenantId(),
                InboxRecipientRealm.END_USER,
                principal.subjectId(),
                entryId);
    }

    /**
     * Download attachment bytes for an own entry only (not a signed URL).
     * 仅本人收件可下载附件字节（不是签名链接）。
     */
    public DownloadedAttachment downloadAttachment(
            EndUserPrincipal principal, String tenantId, String entryId, String attachmentId) {
        InboxEntryView own = getInbox(principal, tenantId, entryId);
        InboxAttachment meta = inbox.findAttachmentForOwnEntry(
                        own.entry().tenantId(),
                        InboxRecipientRealm.END_USER,
                        principal.subjectId(),
                        entryId,
                        attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment not found"));
        StoredObject stored = objects
                .find(own.entry().tenantId(), meta.storageKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment bytes missing"));
        return new DownloadedAttachment(meta, stored.content());
    }

    /** Downloaded attachment metadata + bytes — 附件元数据与字节. */
    public record DownloadedAttachment(InboxAttachment metadata, byte[] content) {
        public DownloadedAttachment {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(content, "content");
        }
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
