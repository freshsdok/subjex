package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.form.FormSubmissionStore.FormSubmissionRow;
import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcFormSubmissionStoreTest — JDBC 表单提交存放测试：写入后可按表单键读回最近几行。
 */
class JdbcFormSubmissionStoreTest {

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-05T07:00:00Z"));
    private JdbcFormSubmissionStore store;

    @BeforeEach
    void openDatabase() {
        var source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        store = new JdbcFormSubmissionStore(new JdbcTemplate(source), clock, new ObjectMapper());
    }

    @Test
    void saveThenListByFormKeyNewestFirst() {
        FormSubmissionRow first = store.save(
                "endpoint-publication",
                "identity-a",
                "operator-a",
                Map.of("serviceName", "billing", "host", "10.0.0.8", "port", 8080),
                "billing@10.0.0.8:8080");
        now.set(Instant.parse("2026-10-05T07:01:00Z"));
        FormSubmissionRow second = store.save(
                "endpoint-publication",
                "identity-b",
                "operator-b",
                Map.of("serviceName", "ledger", "host", "10.0.0.9", "port", 9090),
                "ledger@10.0.0.9:9090");
        store.save("other-form", "identity-c", "operator-c", Map.of("x", "y"), "other");

        List<FormSubmissionRow> rows = store.listByFormKey("endpoint-publication", 10);
        assertEquals(2, rows.size());
        assertEquals(second.submissionId(), rows.get(0).submissionId());
        assertEquals(first.submissionId(), rows.get(1).submissionId());
        assertEquals("operator-b", rows.get(0).loginName());
        assertTrue(rows.get(0).valuesJson().contains("ledger"));
        assertEquals("ledger@10.0.0.9:9090", rows.get(0).resultSummary());
    }
}
