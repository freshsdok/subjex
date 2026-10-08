package com.subjex.entity.generated;

import java.util.List;
import java.util.Optional;

/**
 * ServiceNoteStore — 实体 service-note 的 CRUD 端口（无 ORM）。
 * <p>
 * Codegen port stub. Runtime CRUD uses {@code GenericEntityStore} after Flyway V11 ({@code service_note}).
 * 代码生成端口桩。运行时 CRUD 经 {@code GenericEntityStore}（Flyway V11）。
 */
public interface ServiceNoteStore {

    /** Persist one row — 持久化一行。 */
    void save(ServiceNote row);

    /** Find by primary key — 按主键查找。 */
    Optional<ServiceNote> findById(String noteId);

    /** Recent rows, newest first when ordered by caller — 最近若干行（排序由调用方约定）。 */
    List<ServiceNote> list(int limit);
}
