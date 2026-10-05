package com.subjex.entity.generated;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JdbcServiceNoteStore — JDBC 草图：针对表 {@code service_note} 的薄 CRUD（无 ORM）。
 * <p>
 * Draft only. Replace the unsupported stubs with {@code JdbcTemplate} (or plain JDBC)
 * when wiring into {@code platform-app}. Bilingual comments match platform style.
 * 仅草稿。接入 {@code platform-app} 时用 {@code JdbcTemplate}（或纯 JDBC）替换未实现方法。
 */
public final class JdbcServiceNoteStore implements ServiceNoteStore {

    static final String INSERT_SQL =
            "INSERT INTO service_note (note_id, title, body, priority) VALUES (?, ?, ?, ?)";

    static final String SELECT_BY_ID_SQL =
            "SELECT note_id, title, body, priority FROM service_note WHERE note_id = ?";

    static final String LIST_SQL =
            "SELECT note_id, title, body, priority FROM service_note LIMIT ?";

    public JdbcServiceNoteStore() {}

    @Override
    public void save(ServiceNote row) {
        Objects.requireNonNull(row, "row");
        throw new UnsupportedOperationException(
                "draft JDBC stub — wire JdbcTemplate; SQL is INSERT_SQL / 草稿，请接入 JdbcTemplate");
    }

    @Override
    public Optional<ServiceNote> findById(String noteId) {
        Objects.requireNonNull(noteId, "noteId");
        throw new UnsupportedOperationException(
                "draft JDBC stub — wire JdbcTemplate; SQL is SELECT_BY_ID_SQL / 草稿，请接入 JdbcTemplate");
    }

    @Override
    public List<ServiceNote> list(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        throw new UnsupportedOperationException(
                "draft JDBC stub — wire JdbcTemplate; SQL is LIST_SQL / 草稿，请接入 JdbcTemplate");
    }
}
