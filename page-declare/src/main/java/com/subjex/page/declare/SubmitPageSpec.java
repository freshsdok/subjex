package com.subjex.page.declare;

import java.util.List;

/**
 * SubmitPageSpec — 提交页：填写路径、POST 的 API、提交成功后的跳转、可选积木顺序。
 * <p>
 * {@code redirectTo} is a console path, not an external URL.
 * {@code redirectTo} 是控制台路径，不是外部 URL。
 * {@code blocks} empty means default runtime layout; otherwise ordered catalog block ids.
 * {@code blocks} 为空表示默认运行时布局；否则为目录积木 id 的有序列表。
 */
public record SubmitPageSpec(String path, String apiPath, String redirectTo, List<String> blocks) {
    public SubmitPageSpec {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
