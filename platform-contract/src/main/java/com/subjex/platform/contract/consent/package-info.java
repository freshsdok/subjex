/**
 * Consent — purpose-consent records: grant, withdraw, query-current, append-only history, and a fail-closed require hook.
 * 目的同意记录：授予、撤销、查询当前、追加式历史，以及失败关闭的 require 门禁。
 * <p>
 * The kernel stores no legal text, only a tenant-supplied purpose key and consent-version id. This package is
 * not OAuth client-scope consent ({@code oauth_consent}) and not tenant-level ZIP export.
 * 内核不存放法律正文，只存租户提供的目的键与同意文本版本标识。本包不是 OAuth 客户端 scope 同意，也不是租户级 ZIP 导出。
 */
package com.subjex.platform.contract.consent;
