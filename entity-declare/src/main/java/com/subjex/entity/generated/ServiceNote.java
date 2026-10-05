package com.subjex.entity.generated;

/**
 * ServiceNote — 实体 service-note 的行记录草稿，已检入仓库。
 * <p>
 * Draft row type for table {@code service_note}. Checked in so the build does not run an annotation processor.
 * 对应表 {@code service_note} 的草稿行类型。检入仓库，构建时不需要注解处理器。
 */
public record ServiceNote(String noteId, String title, String body, Integer priority) {
}
