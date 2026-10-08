package com.subjex.entity.generated;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JdbcServiceNoteStore — JDBC 草图：针对表 {@code service_note} 的薄 CRUD（无 ORM）。
 * <p>
 * Draft only (no Spring in this module). Host writes go through {@code GenericEntityStore}
 * after Flyway {@code V11__service_note.sql}; this class remains a codegen checklist stub.
 * SQL constants below remain the draft checklist.
 * 仅草稿（本模块无 Spring）。宿主写入经 {@code GenericEntityStore}（Flyway V11）；本类仍作代码生成核对桩。
 * 下方 SQL 常量仍作草稿核对清单。
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
