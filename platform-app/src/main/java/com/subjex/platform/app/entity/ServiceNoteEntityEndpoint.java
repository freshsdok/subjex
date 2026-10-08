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
 * Backed by JDBC {@link ServiceNoteStore} (Flyway V11 {@code service_note}). Path and JSON shape
 * match the {@code service-note} flow ({@code itemsKey: notes}). Needs {@code page.read}.
 * 由 JDBC {@link ServiceNoteStore}（Flyway V11）支撑。路径与 JSON 形状对齐 {@code service-note} 流程。
 * 需要 {@code page.read}。
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
