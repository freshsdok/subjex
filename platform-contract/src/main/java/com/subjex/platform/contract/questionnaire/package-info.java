/**
 * Questionnaire — 问卷：平台级调研采集能力（题型、答卷、汇总），不是操作员 Form，也不是测验引擎。
 * <p>
 * Distinct from low-code Form ({@code form_submission} / {@code FormCatalog}): respondents answer for
 * aggregation; operators author and export. See ADR 0013. MVP port surface is {@link QuestionnairePort}.
 * 与低代码 Form 区分：答卷人供汇总，操作员编排与导出。见 ADR 0013。
 */
package com.subjex.platform.contract.questionnaire;
