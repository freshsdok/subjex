package com.subjex.page.declare;

import java.util.List;

/**
 * ListPageSpec — 列表页：控制台路径、读取列表的 API、可选的数组字段名、可选积木顺序。
 * <p>
 * {@code itemsKey} names the JSON array field when the API wraps rows (e.g. {@code submissions}).
 * {@code itemsKey} 在 API 把行包在对象里时指出数组字段名（例如 {@code submissions}）。
 * {@code blocks} empty means default runtime layout; otherwise ordered catalog block ids.
 * {@code blocks} 为空表示默认运行时布局；否则为目录积木 id 的有序列表。
 */
public record ListPageSpec(String path, String apiPath, String itemsKey, List<String> blocks) {
    public ListPageSpec {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
