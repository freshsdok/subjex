package com.subjex.platform.app.entity;

import com.subjex.entity.generated.ServiceNote;
import com.subjex.entity.generated.ServiceNoteStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcServiceNoteStore — JDBC 服务备注存储：表 {@code service_note}（Flyway V11），实现生成的端口。
 * <p>
 * Host wiring for the entity-declare draft. {@code entity-declare} stays Spring-free; this bean uses
 * {@link JdbcTemplate}. Save is upsert (update then insert, retry update on race). List orders by
 * {@code note_id} for a stable page (no {@code updated_at} in the promoted draft).
 * 实体草稿在宿主侧的接线。{@code entity-declare} 保持无 Spring；本 Bean 用 {@link JdbcTemplate}。
 * 保存为 upsert。列表按 {@code note_id} 稳定排序（迁入草稿无 {@code updated_at}）。
 */
public final class JdbcServiceNoteStore implements ServiceNoteStore {

    private static final RowMapper<ServiceNote> ROW = (row, rowNum) -> {
        int priority = row.getInt("priority");
        Integer priorityValue = row.wasNull() ? null : priority;
        return new ServiceNote(
                row.getString("note_id"),
                row.getString("title"),
                row.getString("body"),
                priorityValue);
    };

    private final JdbcTemplate jdbc;

    public JdbcServiceNoteStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void save(ServiceNote row) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(row.noteId(), "noteId");
        Objects.requireNonNull(row.title(), "title");
        // Update first, insert when no row exists. Concurrent first insert loses on PK and retries update.
        // 先更新，没有行时再插入。并发首次插入在主键上落败后改为再更新。
        int updated = update(row);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO service_note (note_id, title, body, priority) VALUES (?, ?, ?, ?)",
                        row.noteId(),
                        row.title(),
                        row.body(),
                        row.priority());
            } catch (DuplicateKeyException raced) {
                update(row);
            }
        }
    }

    @Override
    public Optional<ServiceNote> findById(String noteId) {
        Objects.requireNonNull(noteId, "noteId");
        return jdbc.query(
                        "SELECT note_id, title, body, priority FROM service_note WHERE note_id = ?",
                        ROW,
                        noteId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ServiceNote> list(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return jdbc.query(
                "SELECT note_id, title, body, priority FROM service_note ORDER BY note_id LIMIT ?",
                ROW,
                limit);
    }

    private int update(ServiceNote row) {
        return jdbc.update(
                "UPDATE service_note SET title = ?, body = ?, priority = ? WHERE note_id = ?",
                row.title(),
                row.body(),
                row.priority(),
                row.noteId());
    }
}
