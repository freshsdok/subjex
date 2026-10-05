package com.subjex.platform.app.form;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FormSubmissionStore — 表单提交存放：接受的提交写入共享库，并可按表单键列出最近几条。
 * <p>
 * This is not an online form library; only accepted submissions are kept.
 * 这不是在线表单库；只保存已接受的提交。
 */
public interface FormSubmissionStore {

    /**
     * Persist one accepted submission — 持久化一次已接受的提交。
     */
    FormSubmissionRow save(
            String formKey,
            String actorIdentityId,
            String loginName,
            Map<String, Object> values,
            String resultSummary);

    /**
     * Recent rows for one form, newest first — 某一表单的最近几行，最新的在前。
     */
    List<FormSubmissionRow> listByFormKey(String formKey, int limit);

    /**
     * FormSubmissionRow — 一行已接受的提交。
     */
    record FormSubmissionRow(
            String submissionId,
            String formKey,
            String actorIdentityId,
            String loginName,
            String valuesJson,
            String resultSummary,
            Instant submittedAt) {}
}
