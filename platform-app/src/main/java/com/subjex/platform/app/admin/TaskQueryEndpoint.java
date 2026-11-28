package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.contract.task.TaskRecord;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TaskQueryEndpoint — read-only JSON twin of {@code GET /admin/tasks} for the operator console.
 * Lists existing {@code platform_task} rows via {@link JdbcAdminReader#tasks()}. No create, schedule, or cron.
 * 只读任务列表：控制台 JSON 入口，复用已有管理读取，不提交、不排程、不另做任务平台。
 */
@RestController
public class TaskQueryEndpoint {

    /** Console list path — 控制台列表路径. */
    public static final String PATH = JsonApi.BASE + "/tasks";

    private final JdbcAdminReader adminReader;

    public TaskQueryEndpoint(JdbcAdminReader adminReader) {
        this.adminReader = Objects.requireNonNull(adminReader, "adminReader");
    }

    @GetMapping(PATH)
    public TasksDocument list() {
        return new TasksDocument(adminReader.tasks());
    }

    /** Wrapped list — 包装后的列表. */
    public record TasksDocument(List<TaskRecord> tasks) {}
}
