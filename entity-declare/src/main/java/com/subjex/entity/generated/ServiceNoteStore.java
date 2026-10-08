package com.subjex.entity.generated;

import java.util.List;
import java.util.Optional;

/**
 * ServiceNoteStore — 实体 service-note 的 CRUD 端口（无 ORM）。
 * <p>
 * Implemented in {@code platform-app} by JDBC after Flyway V11 ({@code service_note}).
 * 由 {@code platform-app} 在 Flyway V11 后以 JDBC 实现。
 */
public interface ServiceNoteStore {

    /** Persist one row — 持久化一行。 */
    void save(ServiceNote row);

    /** Find by primary key — 按主键查找。 */
    Optional<ServiceNote> findById(String noteId);

    /** Recent rows, newest first when ordered by caller — 最近若干行（排序由调用方约定）。 */
    List<ServiceNote> list(int limit);
}
