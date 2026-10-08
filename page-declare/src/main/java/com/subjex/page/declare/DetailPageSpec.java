package com.subjex.page.declare;

import java.util.List;

/**
 * DetailPageSpec — 详情页：路径模板、读取数据的 API、行标识字段、可选积木顺序。
 * <p>
 * When there is no GET-by-id upstream, the console loads {@code apiPath} and picks the row by {@code idField}.
 * 上游没有按 id 查询时，控制台读 {@code apiPath} 再用 {@code idField} 挑出一行。
 * {@code blocks} empty means default runtime layout; otherwise ordered catalog block ids.
 * {@code blocks} 为空表示默认运行时布局；否则为目录积木 id 的有序列表。
 */
public record DetailPageSpec(String path, String apiPath, String itemsKey, String idField, List<String> blocks) {
    public DetailPageSpec {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
