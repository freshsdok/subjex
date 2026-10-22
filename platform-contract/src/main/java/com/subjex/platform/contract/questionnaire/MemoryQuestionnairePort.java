package com.subjex.platform.contract.questionnaire;

import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MemoryQuestionnairePort — 内存问卷端口：定义/答卷/匿名令牌 + 薄汇总/CSV（测试用）.
 * <p>
 * Q5: {@link #stats} / {@link #exportCsv} use production responses only (exclude OPERATOR preview).
 * Q5：汇总/导出仅生产答卷（排除操作员预览）。
 */
public final class MemoryQuestionnairePort implements QuestionnairePort {

    public static final int DEFAULT_LIST_LIMIT = 50;
    public static final int MAX_LIST_LIMIT = 100;

    private static final RateLimitPort ALWAYS_PERMIT = (tenantId, actionName) -> true;

    private final Clock clock;
    private final RateLimitPort rateLimitPort;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, QuestionnaireDefinition>> byTenant =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CopyOnWriteArrayList<QuestionnaireResponse>>>
            responsesByTenant = new ConcurrentHashMap<>();
    /** tokenId → token row — 令牌索引. */
    private final ConcurrentHashMap<String, QuestionnaireCollectToken> tokensById = new ConcurrentHashMap<>();

    public MemoryQuestionnairePort() {
        this(Clock.systemUTC(), ALWAYS_PERMIT);
    }

    public MemoryQuestionnairePort(Clock clock) {
        this(clock, ALWAYS_PERMIT);
    }

    public MemoryQuestionnairePort(Clock clock, RateLimitPort rateLimitPort) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rateLimitPort = Objects.requireNonNull(rateLimitPort, "rateLimitPort");
    }

    @Override
    public QuestionnaireDefinition create(String tenantId, String title, QuestionnaireCollectMode collectMode) {
        String tenant = requireTenant(tenantId);
        String trimmedTitle = requireTitle(title);
        Objects.requireNonNull(collectMode, "collectMode");
        Instant now = clock.instant();
        QuestionnaireDefinition created = new QuestionnaireDefinition(
                UUID.randomUUID().toString(),
                tenant,
                trimmedTitle,
                QuestionnaireStatus.DRAFT,
                1,
                collectMode,
                List.of(),
                now,
                now);
        byTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(created.questionnaireId(), created);
        return created;
    }

    @Override
    public Optional<QuestionnaireDefinition> get(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, QuestionnaireDefinition> bucket = byTenant.get(tenant);
        if (bucket == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bucket.get(questionnaireId.trim()));
    }

    @Override
    public List<QuestionnaireDefinition> list(String tenantId, int limit) {
        String tenant = requireTenant(tenantId);
        int capped = clampLimit(limit);
        ConcurrentHashMap<String, QuestionnaireDefinition> bucket = byTenant.get(tenant);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        List<QuestionnaireDefinition> rows = new ArrayList<>(bucket.values());
        rows.sort(Comparator.comparing(QuestionnaireDefinition::updatedAt).reversed());
        if (rows.size() > capped) {
            return List.copyOf(rows.subList(0, capped));
        }
        return List.copyOf(rows);
    }

    @Override
    public Optional<QuestionnaireDefinition> replaceItems(
            String tenantId, String questionnaireId, List<QuestionnaireItem> items) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        List<QuestionnaireItem> normalized = QuestionnaireItemSupport.normalize(items);
        ConcurrentHashMap<String, QuestionnaireDefinition> bucket = byTenant.get(tenant);
        if (bucket == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        QuestionnaireDefinition[] holder = new QuestionnaireDefinition[1];
        bucket.computeIfPresent(questionnaireId.trim(), (id, current) -> {
            if (current.status() == QuestionnaireStatus.CLOSED) {
                throw new IllegalStateException("questionnaire CLOSED; items read-only");
            }
            int nextRevision = current.revision();
            if (current.status() == QuestionnaireStatus.PUBLISHED) {
                nextRevision = current.revision() + 1;
            }
            QuestionnaireDefinition updated = new QuestionnaireDefinition(
                    current.questionnaireId(),
                    current.tenantId(),
                    current.title(),
                    current.status(),
                    nextRevision,
                    current.collectMode(),
                    normalized,
                    current.createdAt(),
                    now);
            holder[0] = updated;
            return updated;
        });
        return Optional.ofNullable(holder[0]);
    }

    @Override
    public Optional<QuestionnaireDefinition> publish(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, QuestionnaireDefinition> bucket = byTenant.get(tenant);
        if (bucket == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        QuestionnaireDefinition[] holder = new QuestionnaireDefinition[1];
        bucket.computeIfPresent(questionnaireId.trim(), (id, current) -> {
            if (current.status() != QuestionnaireStatus.DRAFT) {
                throw new IllegalStateException("publish requires DRAFT, was " + current.status());
            }
            if (current.items().isEmpty()) {
                throw new IllegalArgumentException("publish requires at least one item");
            }
            QuestionnaireDefinition published = new QuestionnaireDefinition(
                    current.questionnaireId(),
                    current.tenantId(),
                    current.title(),
                    QuestionnaireStatus.PUBLISHED,
                    current.revision(),
                    current.collectMode(),
                    current.items(),
                    current.createdAt(),
                    now);
            holder[0] = published;
            return published;
        });
        if (holder[0] == null) {
            return Optional.empty();
        }
        if (holder[0].collectMode() == QuestionnaireCollectMode.ANON_LINK) {
            issueCollectToken(tenant, holder[0].questionnaireId());
        }
        return Optional.of(holder[0]);
    }

    @Override
    public Optional<QuestionnaireDefinition> close(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, QuestionnaireDefinition> bucket = byTenant.get(tenant);
        if (bucket == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        QuestionnaireDefinition[] holder = new QuestionnaireDefinition[1];
        bucket.computeIfPresent(questionnaireId.trim(), (id, current) -> {
            if (current.status() != QuestionnaireStatus.PUBLISHED) {
                throw new IllegalStateException("close requires PUBLISHED, was " + current.status());
            }
            QuestionnaireDefinition closed = new QuestionnaireDefinition(
                    current.questionnaireId(),
                    current.tenantId(),
                    current.title(),
                    QuestionnaireStatus.CLOSED,
                    current.revision(),
                    current.collectMode(),
                    current.items(),
                    current.createdAt(),
                    now);
            holder[0] = closed;
            return closed;
        });
        return Optional.ofNullable(holder[0]);
    }

    @Override
    public Optional<QuestionnaireResponse> submit(
            String tenantId,
            String questionnaireId,
            QuestionnaireRespondentRealm respondentRealm,
            String respondentSubjectId,
            List<QuestionnaireAnswer> answers) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        if (respondentRealm == QuestionnaireRespondentRealm.ANON) {
            throw new IllegalArgumentException("use submitAnon for ANON realm");
        }
        Optional<QuestionnaireDefinition> found = get(tenant, questionnaireId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        QuestionnaireDefinition definition = found.get();
        List<QuestionnaireAnswer> normalized =
                QuestionnaireSubmitSupport.validateSubmit(definition, respondentRealm, respondentSubjectId, answers);
        String subject = QuestionnaireSubmitSupport.requireSubject(respondentSubjectId);
        if (respondentRealm == QuestionnaireRespondentRealm.END_USER) {
            boolean duplicate = responsesFor(tenant, definition.questionnaireId()).stream()
                    .anyMatch(row -> row.respondentRealm() == QuestionnaireRespondentRealm.END_USER
                            && row.respondentSubjectId().equals(subject));
            if (duplicate) {
                throw new IllegalStateException("END_USER already submitted for questionnaire");
            }
        }
        return Optional.of(storeResponse(definition, respondentRealm, subject, normalized));
    }

    @Override
    public List<QuestionnaireResponse> listResponses(
            String tenantId, String questionnaireId, QuestionnaireResponseListQuery query) {
        String tenant = requireTenant(tenantId);
        Objects.requireNonNull(query, "query");
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return List.of();
        }
        if (get(tenant, questionnaireId).isEmpty()) {
            return List.of();
        }
        int capped = clampLimit(query.limit());
        List<QuestionnaireResponse> rows = new ArrayList<>(responsesFor(tenant, questionnaireId.trim()));
        if (!query.includePreview()) {
            rows.removeIf(row -> !row.countsForStats());
        }
        rows.sort(Comparator.comparing(QuestionnaireResponse::submittedAt).reversed());
        if (rows.size() > capped) {
            return List.copyOf(rows.subList(0, capped));
        }
        return List.copyOf(rows);
    }

    @Override
    public Optional<QuestionnaireCollectToken> issueCollectToken(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = get(tenant, questionnaireId);
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
        Instant now = clock.instant();
        revokeActiveTokens(definition.questionnaireId(), now);
        QuestionnaireCollectToken token = new QuestionnaireCollectToken(
                UUID.randomUUID().toString(),
                definition.questionnaireId(),
                tenant,
                now,
                null);
        tokensById.put(token.tokenId(), token);
        return Optional.of(token);
    }

    @Override
    public Optional<QuestionnaireCollectToken> revokeCollectToken(String tenantId, String tokenId) {
        String tenant = requireTenant(tenantId);
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        QuestionnaireCollectToken current = tokensById.get(tokenId.trim());
        if (current == null || !current.tenantId().equals(tenant)) {
            return Optional.empty();
        }
        if (!current.active()) {
            return Optional.of(current);
        }
        QuestionnaireCollectToken revoked = new QuestionnaireCollectToken(
                current.tokenId(),
                current.questionnaireId(),
                current.tenantId(),
                current.createdAt(),
                clock.instant());
        tokensById.put(revoked.tokenId(), revoked);
        return Optional.of(revoked);
    }

    @Override
    public Optional<QuestionnaireCollectToken> activeCollectToken(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        if (get(tenant, questionnaireId).isEmpty()) {
            return Optional.empty();
        }
        String qid = questionnaireId.trim();
        return tokensById.values().stream()
                .filter(token -> token.tenantId().equals(tenant)
                        && token.questionnaireId().equals(qid)
                        && token.active())
                .findFirst();
    }

    @Override
    public Optional<QuestionnaireDefinition> getByCollectToken(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        QuestionnaireCollectToken token = tokensById.get(tokenId.trim());
        if (token == null || !token.active()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = get(token.tenantId(), token.questionnaireId());
        if (found.isEmpty() || found.get().status() != QuestionnaireStatus.PUBLISHED) {
            return Optional.empty();
        }
        if (found.get().collectMode() != QuestionnaireCollectMode.ANON_LINK) {
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
        List<QuestionnaireAnswer> normalized = QuestionnaireSubmitSupport.validateSubmit(
                definition, QuestionnaireRespondentRealm.ANON, subject, answers);
        return Optional.of(storeResponse(definition, QuestionnaireRespondentRealm.ANON, subject, normalized));
    }


    @Override
    public Optional<QuestionnaireStats> stats(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = get(tenant, questionnaireId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // Full production set (listResponses is capped) — 全量生产行（listResponses 有上限）。
        List<QuestionnaireResponse> production = responsesFor(tenant, questionnaireId.trim()).stream()
                .filter(QuestionnaireResponse::countsForStats)
                .sorted(Comparator.comparing(QuestionnaireResponse::submittedAt).reversed())
                .toList();
        return Optional.of(QuestionnaireStatsSupport.aggregate(found.get(), production));
    }

    @Override
    public Optional<String> exportCsv(String tenantId, String questionnaireId) {
        String tenant = requireTenant(tenantId);
        if (questionnaireId == null || questionnaireId.isBlank()) {
            return Optional.empty();
        }
        Optional<QuestionnaireDefinition> found = get(tenant, questionnaireId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<QuestionnaireResponse> production = responsesFor(tenant, questionnaireId.trim()).stream()
                .filter(QuestionnaireResponse::countsForStats)
                .sorted(Comparator.comparing(QuestionnaireResponse::submittedAt))
                .toList();
        return Optional.of(QuestionnaireStatsSupport.toCsv(found.get(), production));
    }

    private void revokeActiveTokens(String questionnaireId, Instant at) {
        for (QuestionnaireCollectToken token : tokensById.values()) {
            if (token.questionnaireId().equals(questionnaireId) && token.active()) {
                tokensById.put(
                        token.tokenId(),
                        new QuestionnaireCollectToken(
                                token.tokenId(),
                                token.questionnaireId(),
                                token.tenantId(),
                                token.createdAt(),
                                at));
            }
        }
    }

    private QuestionnaireResponse storeResponse(
            QuestionnaireDefinition definition,
            QuestionnaireRespondentRealm realm,
            String subject,
            List<QuestionnaireAnswer> answers) {
        Instant now = clock.instant();
        QuestionnaireResponse response = new QuestionnaireResponse(
                UUID.randomUUID().toString(),
                definition.questionnaireId(),
                definition.revision(),
                definition.tenantId(),
                realm,
                subject,
                now,
                answers);
        responsesByTenant
                .computeIfAbsent(definition.tenantId(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(definition.questionnaireId(), ignored -> new CopyOnWriteArrayList<>())
                .add(response);
        return response;
    }

    private CopyOnWriteArrayList<QuestionnaireResponse> responsesFor(String tenant, String questionnaireId) {
        ConcurrentHashMap<String, CopyOnWriteArrayList<QuestionnaireResponse>> bucket = responsesByTenant.get(tenant);
        if (bucket == null) {
            return new CopyOnWriteArrayList<>();
        }
        CopyOnWriteArrayList<QuestionnaireResponse> rows = bucket.get(questionnaireId);
        return rows == null ? new CopyOnWriteArrayList<>() : rows;
    }

    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    public static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        return tenantId.trim();
    }

    public static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 512) {
            throw new IllegalArgumentException("title too long");
        }
        return trimmed;
    }
}
