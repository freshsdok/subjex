package com.subjex.entity.generated;

import java.util.List;
import java.util.Optional;

/**
 * ServiceNoteStore — 实体 service-note 的 CRUD 端口桩（无 ORM）。
 * <p>
 * Draft only. Not wired into {@code platform-app} yet.
 * 仅草稿。尚未接入 {@code platform-app}。
 */
public interface ServiceNoteStore {

    /** Persist one row — 持久化一行。 */
    void save(ServiceNote row);

    /** Find by primary key — 按主键查找。 */
    Optional<ServiceNote> findById(String noteId);

    /** Recent rows, newest first when ordered by caller — 最近若干行（排序由调用方约定）。 */
    List<ServiceNote> list(int limit);
}
