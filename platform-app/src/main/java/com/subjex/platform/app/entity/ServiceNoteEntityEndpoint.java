package com.subjex.platform.app.entity;

import com.subjex.entity.generated.ServiceNote;
import com.subjex.entity.generated.ServiceNoteStore;
import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ServiceNoteEntityEndpoint — 服务备注实体只读接口：列表供声明式页面 list/detail 使用。
 * <p>
 * Backed by the step-4 in-memory {@link ServiceNoteStore}. Step 3 swaps the store for JDBC + Flyway;
 * this path and JSON shape stay. Needs {@code page.read} at the security layer.
 * 由步骤 4 内存 {@link ServiceNoteStore} 支撑。步骤 3 换成 JDBC+Flyway 时本路径与 JSON 形状保持。
 * 安全层需要 {@code page.read}。
 */
@RestController
public class ServiceNoteEntityEndpoint {

    /** JSON path for the notes collection — 备注集合的 JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/entities/service-note/notes";

    private final ServiceNoteStore store;

    public ServiceNoteEntityEndpoint(ServiceNoteStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @GetMapping(PATH)
    public NotesDocument list() {
        List<NoteDocument> notes = store.list(100).stream().map(NoteDocument::from).toList();
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
        static NoteDocument from(ServiceNote row) {
            return new NoteDocument(row.noteId(), row.title(), row.body(), row.priority());
        }
    }
}
