package com.subjex.platform.app.questionnaire;

import com.subjex.platform.contract.questionnaire.MemoryQuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionType;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnonKeys;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectToken;
import com.subjex.platform.contract.questionnaire.QuestionnaireRateLimited;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.questionnaire.QuestionnaireItemSupport;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponseListQuery;
import com.subjex.platform.contract.questionnaire.QuestionnaireStats;
import com.subjex.platform.contract.questionnaire.QuestionnaireStatsSupport;
import com.subjex.platform.contract.questionnaire.QuestionnaireStatus;
import com.subjex.platform.contract.questionnaire.QuestionnaireSubmitSupport;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcQuestionnairePort — JDBC 问卷端口：定义/答卷/匿名令牌 + 薄汇总/CSV（无 ORM）。
 * <p>
 * Q5: {@link #stats} / {@link #exportCsv} load production responses only (exclude OPERATOR).
 * Never uses {@code form_submission}.
 * Q5：汇总/导出仅生产答卷；不用 form_submission。
 */
public final class JdbcQuestionnairePort implements QuestionnairePort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RateLimitPort rateLimitPort;

    public JdbcQuestionnairePort(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, RateLimitPort rateLimitPort) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rateLimitPort = Objects.requireNonNull(rateLimitPort, "rateLimitPort");
    }

    @Override
    public QuestionnaireDefinition create(String tenantId, String title, QuestionnaireCollectMode collectMode) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        String trimmedTitle = MemoryQuestionnairePort.requireTitle(title);
        Objects.requireNonNull(collectMode, "collectMode");
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        return tx.execute(status -> {
            jdbc.update(
                    """
                    INSERT INTO questionnaire (
                      questionnaire_id, tenant_id, title, status, revision, collect_mode, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    tenant,
                    trimmedTitle,
                    QuestionnaireStatus.DRAFT.name(),
                    1,
                    collectMode.name(),
                    Timestamp.from(now),
                    Timestamp.from(now));
            return requireGet(tenant, id);
        });
    }

    @Override
    public Optional<QuestionnaireDefinition> get(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        return load(tenant, questionnaireId.trim());
    }

    @Override
    public List<QuestionnaireDefinition> list(String tenantId, int limit) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        int capped = MemoryQuestionnairePort.clampLimit(limit);
        List<QuestionnaireDefinition> headers = jdbc.query(
                """
                SELECT questionnaire_id, tenant_id, title, status, revision, collect_mode, created_at, updated_at
                FROM questionnaire
                WHERE tenant_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapHeader(rs),
                tenant,
                capped);
        List<QuestionnaireDefinition> out = new ArrayList<>(headers.size());
        for (QuestionnaireDefinition header : headers) {
            out.add(withItems(header, loadItems(header.questionnaireId(), header.revision())));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<QuestionnaireDefinition> replaceItems(
            String tenantId, String questionnaireId, List<QuestionnaireItem> items) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        List<QuestionnaireItem> normalized = QuestionnaireItemSupport.normalize(items);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            QuestionnaireDefinition current = found.get();
            if (current.status() == QuestionnaireStatus.CLOSED) {
                throw new IllegalStateException("questionnaire CLOSED; items read-only");
            }
            int writeRevision = current.revision();
            if (current.status() == QuestionnaireStatus.PUBLISHED) {
                writeRevision = current.revision() + 1;
                int bumped = jdbc.update(
                        """
                        UPDATE questionnaire
                        SET revision = ?, updated_at = ?
                        WHERE tenant_id = ? AND questionnaire_id = ? AND revision = ? AND status = ?
                        """,
                        writeRevision,
                        Timestamp.from(now),
                        tenant,
                        current.questionnaireId(),
                        current.revision(),
                        QuestionnaireStatus.PUBLISHED.name());
                if (bumped != 1) {
                    throw new IllegalStateException("concurrent modification on publish-revision bump");
                }
            } else {
                jdbc.update(
                        "DELETE FROM questionnaire_item WHERE questionnaire_id = ? AND revision = ?",
                        current.questionnaireId(),
                        current.revision());
                int touched = jdbc.update(
                        """
                        UPDATE questionnaire SET updated_at = ?
                        WHERE tenant_id = ? AND questionnaire_id = ? AND revision = ? AND status = ?
                        """,
                        Timestamp.from(now),
                        tenant,
                        current.questionnaireId(),
                        current.revision(),
                        QuestionnaireStatus.DRAFT.name());
                if (touched != 1) {
                    throw new IllegalStateException("concurrent modification on draft replace");
                }
            }
            insertItems(current.questionnaireId(), writeRevision, normalized);
            return Optional.of(requireGet(tenant, current.questionnaireId()));
        });
    }

    @Override
    public Optional<QuestionnaireDefinition> publish(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            QuestionnaireDefinition current = found.get();
            if (current.status() != QuestionnaireStatus.DRAFT) {
                throw new IllegalStateException("publish requires DRAFT, was " + current.status());
            }
            if (current.items().isEmpty()) {
                throw new IllegalArgumentException("publish requires at least one item");
            }
            int n = jdbc.update(
                    """
                    UPDATE questionnaire
                    SET status = ?, updated_at = ?
                    WHERE tenant_id = ? AND questionnaire_id = ? AND status = ?
                    """,
                    QuestionnaireStatus.PUBLISHED.name(),
                    Timestamp.from(now),
                    tenant,
                    current.questionnaireId(),
                    QuestionnaireStatus.DRAFT.name());
            if (n != 1) {
                throw new IllegalStateException("concurrent modification on publish");
            }
            QuestionnaireDefinition published = requireGet(tenant, current.questionnaireId());
            if (published.collectMode() == QuestionnaireCollectMode.ANON_LINK) {
                issueCollectTokenInTx(published, clock.instant());
            }
            return Optional.of(published);
        });
    }

    @Override
    public Optional<QuestionnaireDefinition> close(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            QuestionnaireDefinition current = found.get();
            if (current.status() != QuestionnaireStatus.PUBLISHED) {
                throw new IllegalStateException("close requires PUBLISHED, was " + current.status());
            }
            int n = jdbc.update(
                    """
                    UPDATE questionnaire
                    SET status = ?, updated_at = ?
                    WHERE tenant_id = ? AND questionnaire_id = ? AND status = ?
                    """,
                    QuestionnaireStatus.CLOSED.name(),
                    Timestamp.from(now),
                    tenant,
                    current.questionnaireId(),
                    QuestionnaireStatus.PUBLISHED.name());
            if (n != 1) {
                throw new IllegalStateException("concurrent modification on close");
            }
            return Optional.of(requireGet(tenant, current.questionnaireId()));
        });
    }


    @Override
    public Optional<QuestionnaireResponse> submit(
            String tenantId,
            String questionnaireId,
            QuestionnaireRespondentRealm respondentRealm,
            String respondentSubjectId,
            List<QuestionnaireAnswer> answers) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        if (respondentRealm == QuestionnaireRespondentRealm.ANON) {
            throw new IllegalArgumentException("use submitAnon for ANON realm");
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            QuestionnaireDefinition definition = found.get();
            List<QuestionnaireAnswer> normalized = QuestionnaireSubmitSupport.validateSubmit(
                    definition, respondentRealm, respondentSubjectId, answers);
            String subject = QuestionnaireSubmitSupport.requireSubject(respondentSubjectId);
            if (respondentRealm == QuestionnaireRespondentRealm.END_USER) {
                Integer existing = jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM questionnaire_response
                        WHERE questionnaire_id = ? AND tenant_id = ?
                          AND respondent_realm = ? AND respondent_subject_id = ?
                        """,
                        Integer.class,
                        definition.questionnaireId(),
                        tenant,
                        QuestionnaireRespondentRealm.END_USER.name(),
                        subject);
                if (existing != null && existing > 0) {
                    throw new IllegalStateException("END_USER already submitted for questionnaire");
                }
            }
            String responseId = UUID.randomUUID().toString();
            jdbc.update(
                    """
                    INSERT INTO questionnaire_response (
                      response_id, questionnaire_id, revision, tenant_id,
                      respondent_realm, respondent_subject_id, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    responseId,
                    definition.questionnaireId(),
                    definition.revision(),
                    tenant,
                    respondentRealm.name(),
                    subject,
                    Timestamp.from(now));
            for (QuestionnaireAnswer answer : normalized) {
                jdbc.update(
                        """
                        INSERT INTO questionnaire_answer (response_id, item_id, value_json)
                        VALUES (?, ?, ?)
                        """,
                        responseId,
                        answer.itemId(),
                        answer.valueJson());
            }
            return Optional.of(new QuestionnaireResponse(
                    responseId,
                    definition.questionnaireId(),
                    definition.revision(),
                    tenant,
                    respondentRealm,
                    subject,
                    now,
                    normalized));
        });
    }

    @Override
    public List<QuestionnaireResponse> listResponses(
            String tenantId, String questionnaireId, QuestionnaireResponseListQuery query) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        Objects.requireNonNull(query, "query");
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return List.of();
        }
        if (load(tenant, questionnaireId.trim()).isEmpty()) {
            return List.of();
        }
        int capped = MemoryQuestionnairePort.clampLimit(query.limit());
        List<QuestionnaireResponse> headers;
        if (query.includePreview()) {
            headers = jdbc.query(
                    """
                    SELECT response_id, questionnaire_id, revision, tenant_id,
                           respondent_realm, respondent_subject_id, submitted_at
                    FROM questionnaire_response
                    WHERE tenant_id = ? AND questionnaire_id = ?
                    ORDER BY submitted_at DESC
                    LIMIT ?
                    """,
                    this::mapResponseHeader,
                    tenant,
                    questionnaireId.trim(),
                    capped);
        } else {
            headers = jdbc.query(
                    """
                    SELECT response_id, questionnaire_id, revision, tenant_id,
                           respondent_realm, respondent_subject_id, submitted_at
                    FROM questionnaire_response
                    WHERE tenant_id = ? AND questionnaire_id = ? AND respondent_realm <> ?
                    ORDER BY submitted_at DESC
                    LIMIT ?
                    """,
                    this::mapResponseHeader,
                    tenant,
                    questionnaireId.trim(),
                    QuestionnaireRespondentRealm.OPERATOR.name(),
                    capped);
        }
        List<QuestionnaireResponse> out = new ArrayList<>(headers.size());
        for (QuestionnaireResponse header : headers) {
            out.add(withAnswers(header, loadAnswers(header.responseId())));
        }
        return List.copyOf(out);
    }

    private QuestionnaireResponse mapResponseHeader(ResultSet rs, int rowNum) throws SQLException {
        return new QuestionnaireResponse(
                rs.getString("response_id"),
                rs.getString("questionnaire_id"),
                rs.getInt("revision"),
                rs.getString("tenant_id"),
                QuestionnaireRespondentRealm.valueOf(rs.getString("respondent_realm")),
                rs.getString("respondent_subject_id"),
                rs.getTimestamp("submitted_at").toInstant(),
                List.of());
    }

    private List<QuestionnaireAnswer> loadAnswers(String responseId) {

        return jdbc.query(
                """
                SELECT item_id, value_json
                FROM questionnaire_answer
                WHERE response_id = ?
                ORDER BY item_id ASC
                """,
                (rs, rowNum) -> new QuestionnaireAnswer(rs.getString("item_id"), rs.getString("value_json")),
                responseId);
    }

    private static QuestionnaireResponse withAnswers(
            QuestionnaireResponse header, List<QuestionnaireAnswer> answers) {
        return new QuestionnaireResponse(
                header.responseId(),
                header.questionnaireId(),
                header.revision(),
                header.tenantId(),
                header.respondentRealm(),
                header.respondentSubjectId(),
                header.submittedAt(),
                List.copyOf(answers));
    }


    @Override
    public Optional<QuestionnaireCollectToken> issueCollectToken(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            QuestionnaireDefinition definition = found.get();
            if (definition.status() != QuestionnaireStatus.PUBLISHED) {
                throw new IllegalStateException("issueCollectToken requires PUBLISHED, was " + definition.status());
            }
            if (definition.collectMode() != QuestionnaireCollectMode.ANON_LINK) {
                throw new IllegalStateException("issueCollectToken requires ANON_LINK collectMode");
            }
            return Optional.of(issueCollectTokenInTx(definition, now));
        });
    }

    @Override
    public Optional<QuestionnaireCollectToken> revokeCollectToken(String tenantId, String tokenId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            Optional<QuestionnaireCollectToken> found = loadToken(tokenId.trim());
            if (found.isEmpty() || !found.get().tenantId().equals(tenant)) {
                return Optional.empty();
            }
            QuestionnaireCollectToken current = found.get();
            if (!current.active()) {
                return Optional.of(current);
            }
            jdbc.update(
                    "UPDATE questionnaire_collect_token SET revoked_at = ? WHERE token_id = ? AND revoked_at IS NULL",
                    Timestamp.from(now),
                    current.tokenId());
            return loadToken(current.tokenId());
        });
    }

    @Override
    public Optional<QuestionnaireCollectToken> activeCollectToken(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        if (load(tenant, questionnaireId.trim()).isEmpty()) {
            return Optional.empty();
        }
        List<QuestionnaireCollectToken> rows = jdbc.query(
                """
                SELECT token_id, questionnaire_id, tenant_id, created_at, revoked_at
                FROM questionnaire_collect_token
                WHERE tenant_id = ? AND questionnaire_id = ? AND revoked_at IS NULL
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> mapToken(rs),
                tenant,
                questionnaireId.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<QuestionnaireDefinition> getByCollectToken(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        Optional<QuestionnaireCollectToken> token = loadToken(tokenId.trim());
        if (token.isEmpty() || !token.get().active()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = load(token.get().tenantId(), token.get().questionnaireId());
        if (found.isEmpty()
                || found.get().status() != QuestionnaireStatus.PUBLISHED
                || found.get().collectMode() != QuestionnaireCollectMode.ANON_LINK) {
            return Optional.empty();
        }
        return found;
    }

    @Override
    public Optional<QuestionnaireResponse> submitAnon(
            String tokenId, String clientKey, List<QuestionnaireAnswer> answers) {
        String key = QuestionnaireSubmitSupport.requireClientKey(clientKey);
        Optional<QuestionnaireDefinition> found = getByCollectToken(tokenId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        QuestionnaireDefinition definition = found.get();
        String action = QuestionnaireAnonKeys.rateAction(definition.questionnaireId(), key);
        if (!rateLimitPort.permit(definition.tenantId(), action)) {
            throw new QuestionnaireRateLimited();
        }
        String subject = QuestionnaireAnonKeys.opaqueSubject(tokenId.trim(), key);
        Instant now = clock.instant();
        return tx.execute(status -> {
            if (getByCollectToken(tokenId).isEmpty()) {
                return Optional.empty();
            }
            List<QuestionnaireAnswer> normalized = QuestionnaireSubmitSupport.validateSubmit(
                    definition, QuestionnaireRespondentRealm.ANON, subject, answers);
            String responseId = UUID.randomUUID().toString();
            jdbc.update(
                    """
                    INSERT INTO questionnaire_response (
                      response_id, questionnaire_id, revision, tenant_id,
                      respondent_realm, respondent_subject_id, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    responseId,
                    definition.questionnaireId(),
                    definition.revision(),
                    definition.tenantId(),
                    QuestionnaireRespondentRealm.ANON.name(),
                    subject,
                    Timestamp.from(now));
            for (QuestionnaireAnswer answer : normalized) {
                jdbc.update(
                        """
                        INSERT INTO questionnaire_answer (response_id, item_id, value_json)
                        VALUES (?, ?, ?)
                        """,
                        responseId,
                        answer.itemId(),
                        answer.valueJson());
            }
            return Optional.of(new QuestionnaireResponse(
                    responseId,
                    definition.questionnaireId(),
                    definition.revision(),
                    definition.tenantId(),
                    QuestionnaireRespondentRealm.ANON,
                    subject,
                    now,
                    normalized));
        });
    }

    private QuestionnaireCollectToken issueCollectTokenInTx(QuestionnaireDefinition definition, Instant now) {
        jdbc.update(
                """
                UPDATE questionnaire_collect_token
                SET revoked_at = ?
                WHERE questionnaire_id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(now),
                definition.questionnaireId());
        String tokenId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO questionnaire_collect_token (
                  token_id, questionnaire_id, tenant_id, created_at, revoked_at)
                VALUES (?, ?, ?, ?, NULL)
                """,
                tokenId,
                definition.questionnaireId(),
                definition.tenantId(),
                Timestamp.from(now));
        return new QuestionnaireCollectToken(
                tokenId, definition.questionnaireId(), definition.tenantId(), now, null);
    }

    private Optional<QuestionnaireCollectToken> loadToken(String tokenId) {
        List<QuestionnaireCollectToken> rows = jdbc.query(
                """
                SELECT token_id, questionnaire_id, tenant_id, created_at, revoked_at
                FROM questionnaire_collect_token
                WHERE token_id = ?
                """,
                (rs, rowNum) -> mapToken(rs),
                tokenId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static QuestionnaireCollectToken mapToken(ResultSet rs) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new QuestionnaireCollectToken(
                rs.getString("token_id"),
                rs.getString("questionnaire_id"),
                rs.getString("tenant_id"),
                rs.getTimestamp("created_at").toInstant(),
                revoked == null ? null : revoked.toInstant());
    }


    @Override
    public Optional<QuestionnaireStats> stats(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<QuestionnaireResponse> production = loadProductionResponses(tenant, questionnaireId.trim());
        return Optional.of(QuestionnaireStatsSupport.aggregate(found.get(), production));
    }

    @Override
    public Optional<String> exportCsv(String tenantId, String questionnaireId) {
        String tenant = MemoryQuestionnairePort.requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = load(tenant, questionnaireId.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<QuestionnaireResponse> production = loadProductionResponses(tenant, questionnaireId.trim());
        // CSV oldest-first for stable export — CSV 按时间升序便于稳定导出。
        production = new ArrayList<>(production);
        production.sort(java.util.Comparator.comparing(QuestionnaireResponse::submittedAt));
        return Optional.of(QuestionnaireStatsSupport.toCsv(found.get(), production));
    }

    private List<QuestionnaireResponse> loadProductionResponses(String tenant, String questionnaireId) {
        List<QuestionnaireResponse> headers = jdbc.query(
                """
                SELECT response_id, questionnaire_id, revision, tenant_id,
                       respondent_realm, respondent_subject_id, submitted_at
                FROM questionnaire_response
                WHERE tenant_id = ? AND questionnaire_id = ? AND respondent_realm <> ?
                ORDER BY submitted_at DESC
                """,
                this::mapResponseHeader,
                tenant,
                questionnaireId,
                QuestionnaireRespondentRealm.OPERATOR.name());
        List<QuestionnaireResponse> out = new ArrayList<>(headers.size());
        for (QuestionnaireResponse header : headers) {
            out.add(withAnswers(header, loadAnswers(header.responseId())));
        }
        return List.copyOf(out);
    }

    private void insertItems(String questionnaireId, int revision, List<QuestionnaireItem> items) {
        for (QuestionnaireItem item : items) {
            jdbc.update(
                    """
                    INSERT INTO questionnaire_item (
                      questionnaire_id, revision, item_id, sort_order, question_type, prompt, required, options_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    questionnaireId,
                    revision,
                    item.itemId(),
                    item.sortOrder(),
                    item.questionType().name(),
                    item.prompt(),
                    item.required(),
                    item.optionsJson());
        }
    }

    private QuestionnaireDefinition requireGet(String tenant, String questionnaireId) {
        return load(tenant, questionnaireId)
                .orElseThrow(() -> new IllegalStateException("questionnaire missing after write"));
    }

    private Optional<QuestionnaireDefinition> load(String tenant, String questionnaireId) {
        List<QuestionnaireDefinition> rows = jdbc.query(
                """
                SELECT questionnaire_id, tenant_id, title, status, revision, collect_mode, created_at, updated_at
                FROM questionnaire
                WHERE tenant_id = ? AND questionnaire_id = ?
                """,
                (rs, rowNum) -> mapHeader(rs),
                tenant,
                questionnaireId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        QuestionnaireDefinition header = rows.get(0);
        return Optional.of(withItems(header, loadItems(header.questionnaireId(), header.revision())));
    }

    private List<QuestionnaireItem> loadItems(String questionnaireId, int revision) {
        return jdbc.query(
                """
                SELECT item_id, sort_order, question_type, prompt, required, options_json
                FROM questionnaire_item
                WHERE questionnaire_id = ? AND revision = ?
                ORDER BY sort_order ASC, item_id ASC
                """,
                (rs, rowNum) -> new QuestionnaireItem(
                        rs.getString("item_id"),
                        rs.getInt("sort_order"),
                        QuestionType.valueOf(rs.getString("question_type")),
                        rs.getString("prompt"),
                        rs.getBoolean("required"),
                        rs.getString("options_json")),
                questionnaireId,
                revision);
    }

    private static QuestionnaireDefinition mapHeader(ResultSet rs) throws SQLException {
        return new QuestionnaireDefinition(
                rs.getString("questionnaire_id"),
                rs.getString("tenant_id"),
                rs.getString("title"),
                QuestionnaireStatus.valueOf(rs.getString("status")),
                rs.getInt("revision"),
                QuestionnaireCollectMode.valueOf(rs.getString("collect_mode")),
                List.of(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static QuestionnaireDefinition withItems(QuestionnaireDefinition header, List<QuestionnaireItem> items) {
        return new QuestionnaireDefinition(
                header.questionnaireId(),
                header.tenantId(),
                header.title(),
                header.status(),
                header.revision(),
                header.collectMode(),
                List.copyOf(items),
                header.createdAt(),
                header.updatedAt());
    }
}
