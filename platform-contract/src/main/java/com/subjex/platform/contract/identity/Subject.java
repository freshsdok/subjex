package com.subjex.platform.contract.identity;

/**
 * Subject — 主体：平台所记录事实指向的人或组织。
 * <p>
 * A subject is not a login and not a tenant. The same person can be a subject in more than one tenant through different identities.
 * 主体不是登录，也不是租户。同一个人可以通过不同身份出现在多个租户里。
 */
public record Subject(String subjectId, String subjectName, SubjectKind subjectKind) {
}
