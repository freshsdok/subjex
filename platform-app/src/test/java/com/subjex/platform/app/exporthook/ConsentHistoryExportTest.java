package com.subjex.platform.app.exporthook;

import static org.assertj.core.api.Assertions.assertThat;

import com.subjex.platform.app.consent.JdbcConsentPort;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.app.support.TenantKeyset;
import com.subjex.platform.contract.consent.ConsentEvent;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.identity.Identity;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ConsentHistoryExportTest — personal-data export streams consent history in keyset pages of 50.
 * 个人资料导出按 50 条键集分页流式写出同意历史，不把整段历史装进一张列表。
 */
class ConsentHistoryExportTest {

    private static final String TENANT = "tenant-export-history";
    private static final String PURPOSE = "platform.export.history";
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void historyLargerThanOnePageIsStreamedInFullOldestFirst(H2PlatformTables.Mode mode) throws Exception {
        DataSource ds = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT,
                "History Tenant");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        AtomicInteger ticks = new AtomicInteger();
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return T0.plusSeconds(ticks.getAndIncrement());
            }
        };
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        ConsentPort consents = new JdbcConsentPort(jdbc, tx, clock, new JdbcAuditPort(jdbc));
        EndUserRecord alice = accounts.provision(TENANT, "Alice");
        Identity identity = accounts.findActiveIdentity(alice.account().accountId(), TENANT).orElseThrow();
        int total = TenantKeyset.EXPORT_PAGE_LIMIT + 10;
        for (int i = 1; i <= total; i++) {
            consents.grant(
                    TENANT,
                    alice.subject().subjectId(),
                    PURPOSE,
                    "v" + i,
                    identity.identityId());
        }
        List<ConsentEvent> history = consents.history(TENANT, alice.subject().subjectId(), PURPOSE);
        assertThat(history).hasSize(total);

        GenericSubjectRecordExporter exporter = new GenericSubjectRecordExporter(
                jdbc, consents, null, null, new JdbcObjectGrantStore(jdbc, clock), clock);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        exporter.exportTo(TENANT, alice.subject().subjectId(), "ALL_OWN", buffer);
        JsonNode bundle = MAPPER.readTree(buffer.toString(StandardCharsets.UTF_8));
        JsonNode events = bundle.get("consents").get("history");
        assertThat(events.size()).isEqualTo(total);
        assertThat(events.get(0).get("eventId").asText()).isEqualTo(history.get(0).eventId());
        assertThat(events.get(total - 1).get("eventId").asText()).isEqualTo(history.get(total - 1).eventId());
        assertThat(events.get(0).get("consentVersionId").asText()).isEqualTo("v1");
        assertThat(events.get(total - 1).get("consentVersionId").asText()).isEqualTo("v" + total);
        assertThat(events.get(0).get("purposeKey").asText()).isEqualTo(PURPOSE);
        assertThat(bundle.get("consents").get("current").size()).isEqualTo(1);
        assertThat(bundle.get("consents").get("current").get(0).get("consentVersionId").asText())
                .isEqualTo("v" + total);
    }
}
