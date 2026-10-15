package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ServiceNoteEntityEndpoint — 服务备注实体只读接口：列表供声明式页面 list/detail 使用。
 * <p>
 * Compatibility shim for the {@code service-note} flow ({@code itemsKey: notes}). List reads route by
 * {@code storageMode}: hybrid → {@link HybridEntityStore}, table → {@link GenericEntityStore} (HY-1
 * classpath sample is hybrid). Writes use {@code entity.record.upsert} with the same routing.
 * Prefer the generic {@code /api/v1/entities/service-note/records} path for new clients. Needs
 * {@code page.read} on the generic path; this shim stays authenticated-only like before.
 * 兼容垫片：列表按 storageMode 路由（样例 HY-1 为 hybrid）；写经 {@code entity.record.upsert}。
 * 新客户端优先用 {@code /records}。
 */
@RestController
public class ServiceNoteEntityEndpoint {

    /** JSON path for the notes collection — 备注集合的 JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/entities/service-note/notes";

    private static final String ENTITY_KEY = "service-note";
    private static final int LIST_LIMIT = 100;

    private final EntityCatalog catalog;
    private final GenericEntityStore tableStore;
    private final HybridEntityStore hybridStore;

    public ServiceNoteEntityEndpoint(
            EntityCatalog catalog, GenericEntityStore tableStore, HybridEntityStore hybridStore) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tableStore = Objects.requireNonNull(tableStore, "tableStore");
        this.hybridStore = Objects.requireNonNull(hybridStore, "hybridStore");
    }

    @GetMapping(PATH)
    public NotesDocument list() {
        RenderedEntity entity = catalog
                .find(ENTITY_KEY)
                .orElseThrow(() -> new IllegalStateException("service-note entity missing from catalog"));
        List<Map<String, Object>> rows =
                entity.sharedRecordStorage()
                        ? hybridStore.list(entity, LIST_LIMIT, null)
                        : tableStore.list(entity, LIST_LIMIT);
        List<NoteDocument> notes = rows.stream().map(NoteDocument::fromRow).toList();
        return new NotesDocument(notes);
    }

    /**
     * NotesDocument — 备注列表：{@code notes} 键与 flow 声明的 {@code itemsKey} 对齐。
     */
    public record NotesDocument(List<NoteDocument> notes) {}

    /**
     * NoteDocument — 一行备注：字段名与实体 / 表单 camelCase 对齐，供详情按 {@code noteId} 挑选。
     */
    public record NoteDocument(String noteId, String title, String body, Integer priority) {
        static NoteDocument fromRow(Map<String, Object> row) {
            Object priority = row.get("priority");
            Integer priorityValue = null;
            if (priority instanceof Integer i) {
                priorityValue = i;
            } else if (priority instanceof Number n) {
                // JSON attrs may yield Long / etc. — jsonb attrs 可能是 Long 等
                priorityValue = n.intValue();
            }
            return new NoteDocument(
                    (String) row.get("noteId"),
                    (String) row.get("title"),
                    (String) row.get("body"),
                    priorityValue);
        }
    }
}
