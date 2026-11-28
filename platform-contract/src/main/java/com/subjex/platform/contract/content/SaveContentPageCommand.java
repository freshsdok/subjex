package com.subjex.platform.contract.content;

import java.util.List;

/**
 * Create or replace the editable fields of a content page. Status is not changed here.
 * 创建或覆盖内容页的可编辑字段。本命令不改变发布状态。
 */
public record SaveContentPageCommand(
        String tenantId,
        String pageId,
        String slug,
        String title,
        List<ContentBlock> blocks,
        String actorSubjectId) {

    public SaveContentPageCommand {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
