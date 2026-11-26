package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.app.storage.LocalHmacDownloadIssuer;
import com.subjex.platform.app.storage.LocalDownloadSignature;
import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectAccessAuthorizer;
import com.subjex.platform.app.storage.ObjectDownloadLinkService;
import com.subjex.platform.app.storage.ObjectDownloadProperties;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.storage.ObjectStorage;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared test wiring for {@link EndUserShellService} after profile fields were added.
 * 资料字段加入后，终端外壳测试的共用装配。
 */
final class EndUserShellFixtures {

    private EndUserShellFixtures() {}

    static Kit shell(
            EndUserAccountStore accounts,
            InboxPort inbox,
            QuestionnairePort questionnaires,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            Clock clock,
            ObjectStorage storage) {
        JdbcAuditPort audit = new JdbcAuditPort(jdbc);
        ObjectAccessAudit accessAudit = new ObjectAccessAudit(audit, clock);
        JdbcObjectGrantStore grants = new JdbcObjectGrantStore(jdbc, clock, accessAudit);
        LocalDownloadSignature signature = new LocalDownloadSignature("test-hmac-key");
        ObjectDownloadProperties properties = new ObjectDownloadProperties(
                ObjectDownloadProperties.DEFAULT_TTL,
                ObjectDownloadProperties.HARD_CAP,
                "http://127.0.0.1:8080",
                "test-hmac-key");
        ObjectDownloadLinkService downloads = new ObjectDownloadLinkService(
                new ObjectAccessAuthorizer(grants, jdbc, clock),
                new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock),
                signature,
                storage,
                properties,
                clock,
                accessAudit);
        VerificationCodePort codes = new MemoryVerificationCodePort(
                clock, Duration.ofMinutes(10), true, new DevVerificationCodeSender());
        EndUserShellService service = new EndUserShellService(
                accounts,
                inbox,
                questionnaires,
                audit,
                storage,
                new JdbcEndUserProfileStore(jdbc, tx, clock),
                grants,
                downloads,
                codes,
                clock);
        return new Kit(service, grants, codes, downloads);
    }

    record Kit(
            EndUserShellService shell,
            JdbcObjectGrantStore grants,
            VerificationCodePort codes,
            ObjectDownloadLinkService downloads) {}
}
