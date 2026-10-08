package com.subjex.form.generated;

/**
 * ServiceNote — 服务备注：从表单 service-note 生成的记录，已检入仓库。
 * <p>
 * Service note. Checked in so the build does not run an annotation processor.
 * 检入仓库，构建时不需要注解处理器。
 */
public record ServiceNote(String noteId, String title, String body, Integer priority) {
}
