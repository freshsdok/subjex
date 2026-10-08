package com.subjex.platform.app.entity;

import com.subjex.entity.generated.ServiceNote;
import com.subjex.entity.generated.ServiceNoteStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryServiceNoteStore — 进程内服务备注存储：步骤 4 样例闭环用，替代尚未接入的 Flyway JDBC。
 * <p>
 * Implements the generated {@link ServiceNoteStore} port. Step 3 replaces this bean with a JDBC
 * implementation backed by the entity-declare migration draft moved into {@code platform-app} Flyway.
 * Not durable across restarts. Seeds one demo row so the console list is not empty on first open.
 * 实现生成的 {@link ServiceNoteStore} 端口。步骤 3 用迁入 Flyway 的 JDBC 实现替换本 Bean。
 * 进程重启不保留。预置一行演示数据，便于控制台首次打开列表非空。
 */
public final class InMemoryServiceNoteStore implements ServiceNoteStore {

    private final Map<String, ServiceNote> byId = new ConcurrentHashMap<>();
    /** Insertion order for list (newest last; list() reverses) — 插入顺序（list 时倒序）。 */
    private final List<String> order = new ArrayList<>();
    private final Object lock = new Object();

    public InMemoryServiceNoteStore() {
        save(new ServiceNote(
                "demo-note-1",
                "Welcome note",
                "In-memory stub until step 3 wires Flyway. / 步骤 3 接入 Flyway 前的内存桩。",
                1));
    }

    @Override
    public void save(ServiceNote row) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(row.noteId(), "noteId");
        synchronized (lock) {
            boolean fresh = !byId.containsKey(row.noteId());
            byId.put(row.noteId(), row);
            if (fresh) {
                order.add(row.noteId());
            }
        }
    }

    @Override
    public Optional<ServiceNote> findById(String noteId) {
        Objects.requireNonNull(noteId, "noteId");
        return Optional.ofNullable(byId.get(noteId));
    }

    @Override
    public List<ServiceNote> list(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        synchronized (lock) {
            List<ServiceNote> newestFirst = new ArrayList<>(limit);
            for (int i = order.size() - 1; i >= 0 && newestFirst.size() < limit; i--) {
                ServiceNote row = byId.get(order.get(i));
                if (row != null) {
                    newestFirst.add(row);
                }
            }
            return List.copyOf(newestFirst);
        }
    }

    /** Snapshot for tests — 测试用快照。 */
    Map<String, ServiceNote> snapshot() {
        return Map.copyOf(new LinkedHashMap<>(byId));
    }
}
