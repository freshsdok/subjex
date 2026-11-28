package com.subjex.platform.app.content;

import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.content.ContentBlock;
import com.subjex.platform.contract.content.ContentBlockType;
import com.subjex.platform.contract.content.ContentPageDocument;
import com.subjex.platform.contract.content.ContentPageNotFoundException;
import com.subjex.platform.contract.content.ContentPagePort;
import com.subjex.platform.contract.content.ContentPageSlugConflictException;
import com.subjex.platform.contract.content.ContentPageStatus;
import com.subjex.platform.contract.content.ContentPageSummary;
import com.subjex.platform.contract.content.ContentTextFormat;
import com.subjex.platform.contract.content.SaveContentPageCommand;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcContentPagePort — tenant-scoped content pages with append-replaced structured blocks.
 * 按租户的内容页，内容块在每次保存时整体替换。
 * <p>
 * Reserved tenant {@code platform} is never a business home. Drafts are not returned by
 * {@link #getPublishedBySlug}.
 * 保留租户 platform 不是业务归属。{@link #getPublishedBySlug} 不返回草稿。
 */
public final class JdbcContentPagePort implements ContentPagePort {

    public static final String RESERVED_TENANT = "platform";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcContentPagePort(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ContentPageSummary> list(String tenantId, ContentPageStatus status, int limit) {
        String tenant = requireActiveBusinessTenant(tenantId);
        int cap = ContentPageRules.clampLimit(limit);
        if (status == null) {
            return jdbc.query(
                    "SELECT page_id, tenant_id, slug, title, page_status, updated_at, published_at "
                            + "FROM content_page WHERE tenant_id = ? "
                            + "ORDER BY updated_at DESC, page_id DESC LIMIT ?",
                    this::mapSummary,
                    tenant,
                    cap);
        }
        return jdbc.query(
                "SELECT page_id, tenant_id, slug, title, page_status, updated_at, published_at "
                        + "FROM content_page WHERE tenant_id = ? AND page_status = ? "
                        + "ORDER BY updated_at DESC, page_id DESC LIMIT ?",
                this::mapSummary,
                tenant,
                status.name(),
                cap);
    }

    @Override
    public ContentPageDocument get(String tenantId, String pageId) {
        String tenant = requireActiveBusinessTenant(tenantId);
        return loadRequired(tenant, ContentPageRules.requirePageId(pageId));
    }

    @Override
    public Optional<ContentPageDocument> getPublishedBySlug(String tenantId, String slug) {
        String tenant = requireActiveBusinessTenant(tenantId);
        String normalized = ContentPageRules.requireSlug(slug);
        List<ContentPageDocument> rows = jdbc.query(
                "SELECT page_id, tenant_id, slug, title, page_status, created_at, updated_at, published_at "
                        + "FROM content_page WHERE tenant_id = ? AND slug = ? AND page_status = ?",
                this::mapPage,
                tenant,
                normalized,
                ContentPageStatus.PUBLISHED.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ContentPageDocument page = rows.get(0);
        return Optional.of(new ContentPageDocument(
                page.pageId(),
                page.tenantId(),
                page.slug(),
                page.title(),
                page.status(),
                page.createdAt(),
                page.updatedAt(),
                page.publishedAt(),
                loadBlocks(page.pageId()).stream()
                        .map(block -> ContentPageRules.withPublicMedia(block, page.slug(), page.tenantId()))
                        .toList()));
    }

    @Override
    public ContentPageDocument create(SaveContentPageCommand command) {
        Objects.requireNonNull(command, "command");
        String tenant = requireActiveBusinessTenant(command.tenantId());
        String slug = ContentPageRules.requireSlug(command.slug());
        String title = ContentPageRules.requireTitle(command.title());
        String actor = ContentPageRules.requireActor(command.actorSubjectId());
        List<ContentBlock> blocks = ContentPageRules.normalizeBlocks(command.blocks(), id -> objectInTenant(tenant, id));
        Instant now = clock.instant();
        String pageId = "cpg-" + UUID.randomUUID().toString().replace("-", "");
        return transactions.execute(status -> {
            try {
                jdbc.update(
                        "INSERT INTO content_page ("
                                + "page_id, tenant_id, slug, title, page_status, created_at, updated_at, "
                                + "published_at, created_by_subject_id, updated_by_subject_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                        pageId,
                        tenant,
                        slug,
                        title,
                        ContentPageStatus.DRAFT.name(),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        actor,
                        actor);
            } catch (DuplicateKeyException ex) {
                throw new ContentPageSlugConflictException(
                        "slug already used in tenant " + tenant + ": " + slug);
            }
            replaceBlocks(tenant, pageId, blocks);
            return loadRequired(tenant, pageId);
        });
    }

    @Override
    public ContentPageDocument update(SaveContentPageCommand command) {
        Objects.requireNonNull(command, "command");
        String tenant = requireActiveBusinessTenant(command.tenantId());
        String pageId = ContentPageRules.requirePageId(command.pageId());
        String slug = ContentPageRules.requireSlug(command.slug());
        String title = ContentPageRules.requireTitle(command.title());
        String actor = ContentPageRules.requireActor(command.actorSubjectId());
        List<ContentBlock> blocks = ContentPageRules.normalizeBlocks(command.blocks(), id -> objectInTenant(tenant, id));
        Instant now = clock.instant();
        return transactions.execute(status -> {
            loadRequired(tenant, pageId);
            try {
                int updated = jdbc.update(
                        "UPDATE content_page SET slug = ?, title = ?, updated_at = ?, updated_by_subject_id = ? "
                                + "WHERE tenant_id = ? AND page_id = ?",
                        slug,
                        title,
                        Timestamp.from(now),
                        actor,
                        tenant,
                        pageId);
                if (updated == 0) {
                    throw new ContentPageNotFoundException("content page not found in tenant " + tenant);
                }
            } catch (DuplicateKeyException ex) {
                throw new ContentPageSlugConflictException(
                        "slug already used in tenant " + tenant + ": " + slug);
            }
            replaceBlocks(tenant, pageId, blocks);
            return loadRequired(tenant, pageId);
        });
    }

    @Override
    public ContentPageDocument publish(String tenantId, String pageId, String actorSubjectId) {
        return setStatus(
                tenantId, pageId, actorSubjectId, ContentPageStatus.PUBLISHED, true);
    }

    @Override
    public ContentPageDocument unpublish(String tenantId, String pageId, String actorSubjectId) {
        return setStatus(
                tenantId, pageId, actorSubjectId, ContentPageStatus.DRAFT, false);
    }

    private ContentPageDocument setStatus(
            String tenantId, String pageId, String actorSubjectId, ContentPageStatus status, boolean stampPublishedAt) {
        String tenant = requireActiveBusinessTenant(tenantId);
        String id = ContentPageRules.requirePageId(pageId);
        String actor = ContentPageRules.requireActor(actorSubjectId);
        Instant now = clock.instant();
        return transactions.execute(tx -> {
            loadRequired(tenant, id);
            if (stampPublishedAt) {
                jdbc.update(
                        "UPDATE content_page SET page_status = ?, published_at = ?, updated_at = ?, "
                                + "updated_by_subject_id = ? WHERE tenant_id = ? AND page_id = ?",
                        status.name(),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        actor,
                        tenant,
                        id);
            } else {
                jdbc.update(
                        "UPDATE content_page SET page_status = ?, updated_at = ?, updated_by_subject_id = ? "
                                + "WHERE tenant_id = ? AND page_id = ?",
                        status.name(),
                        Timestamp.from(now),
                        actor,
                        tenant,
                        id);
            }
            return loadRequired(tenant, id);
        });
    }

    boolean objectInTenant(String tenantId, String objectId) {
        Integer found = jdbc.query(
                "SELECT 1 FROM object_descriptor WHERE tenant_id = ? AND object_id = ?",
                rs -> rs.next() ? 1 : null,
                tenantId,
                objectId);
        return found != null;
    }

    Optional<ObjectRef> findPublishedImage(String tenantId, String slug, String objectId) {
        String tenant = requireActiveBusinessTenant(tenantId);
        String normalized = ContentPageRules.requireSlug(slug);
        String id = objectId == null ? "" : objectId.trim();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        List<ObjectRef> rows = jdbc.query(
                "SELECT d.object_id, d.object_key "
                        + "FROM content_page p "
                        + "JOIN content_page_block b ON b.page_id = p.page_id AND b.tenant_id = p.tenant_id "
                        + "JOIN object_descriptor d ON d.object_id = b.object_id AND d.tenant_id = p.tenant_id "
                        + "WHERE p.tenant_id = ? AND p.slug = ? AND p.page_status = ? "
                        + "AND b.block_type = 'IMAGE' AND b.object_id = ?",
                (row, n) -> new ObjectRef(row.getString("object_id"), row.getString("object_key")),
                tenant,
                normalized,
                ContentPageStatus.PUBLISHED.name(),
                id);
        return rows.stream().findFirst();
    }

    record ObjectRef(String objectId, String objectKey) {}

    private ContentPageDocument loadRequired(String tenant, String pageId) {
        List<ContentPageDocument> rows = jdbc.query(
                "SELECT page_id, tenant_id, slug, title, page_status, created_at, updated_at, published_at "
                        + "FROM content_page WHERE tenant_id = ? AND page_id = ?",
                this::mapPage,
                tenant,
                pageId);
        if (rows.isEmpty()) {
            throw new ContentPageNotFoundException("content page not found in tenant " + tenant);
        }
        ContentPageDocument page = rows.get(0);
        return new ContentPageDocument(
                page.pageId(),
                page.tenantId(),
                page.slug(),
                page.title(),
                page.status(),
                page.createdAt(),
                page.updatedAt(),
                page.publishedAt(),
                loadBlocks(page.pageId()));
    }

    private List<ContentBlock> loadBlocks(String pageId) {
        return jdbc.query(
                "SELECT block_id, block_type, text_body, text_format, object_id, href, alt_text "
                        + "FROM content_page_block WHERE page_id = ? ORDER BY sort_order ASC, block_id ASC",
                this::mapBlock,
                pageId);
    }

    private void replaceBlocks(String tenant, String pageId, List<ContentBlock> blocks) {
        jdbc.update("DELETE FROM content_page_block WHERE tenant_id = ? AND page_id = ?", tenant, pageId);
        int order = 0;
        for (ContentBlock block : blocks) {
            String blockId = block.blockId() != null && !block.blockId().isBlank()
                    ? block.blockId().trim()
                    : "cpb-" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update(
                    "INSERT INTO content_page_block ("
                            + "block_id, page_id, tenant_id, sort_order, block_type, text_body, text_format, "
                            + "object_id, href, alt_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    blockId,
                    pageId,
                    tenant,
                    order++,
                    block.type().name(),
                    block.text(),
                    block.format() == null ? null : block.format().name(),
                    block.objectId(),
                    block.href(),
                    block.alt());
        }
    }

    private ContentPageSummary mapSummary(ResultSet row, int index) throws SQLException {
        return new ContentPageSummary(
                row.getString("page_id"),
                row.getString("tenant_id"),
                row.getString("slug"),
                row.getString("title"),
                ContentPageStatus.valueOf(row.getString("page_status")),
                timestamp(row, "updated_at"),
                timestamp(row, "published_at"));
    }

    private ContentPageDocument mapPage(ResultSet row, int index) throws SQLException {
        return new ContentPageDocument(
                row.getString("page_id"),
                row.getString("tenant_id"),
                row.getString("slug"),
                row.getString("title"),
                ContentPageStatus.valueOf(row.getString("page_status")),
                timestamp(row, "created_at"),
                timestamp(row, "updated_at"),
                timestamp(row, "published_at"),
                List.of());
    }

    private ContentBlock mapBlock(ResultSet row, int index) throws SQLException {
        String format = row.getString("text_format");
        return new ContentBlock(
                row.getString("block_id"),
                ContentBlockType.valueOf(row.getString("block_type")),
                row.getString("text_body"),
                format == null || format.isBlank() ? null : ContentTextFormat.valueOf(format),
                row.getString("object_id"),
                row.getString("href"),
                row.getString("alt_text"),
                null);
    }

    private static Instant timestamp(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException("reserved tenant " + RESERVED_TENANT + " is not a content home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        if (!TenantState.ACTIVE.name().equals(states.get(0))) {
            throw new TenantDisabledException(id);
        }
        return id;
    }
}
