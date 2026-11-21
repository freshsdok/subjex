package com.subjex.platform.app.storage;

/**
 * ObjectGrantKind — explicit grantee is a subject or an organization (ADR 0015 / WP3-1).
 * 显式被授权方是主体或组织。成员关系本身不是授权。
 */
public enum ObjectGrantKind {
    SUBJECT,
    ORGANIZATION
}
