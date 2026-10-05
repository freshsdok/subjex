package com.subjex.page.declare;

/**
 * PageDefinitionRejected — 页面定义被拒绝：声明式流程本身不合法。
 * <p>
 * Nothing was written to disk or to a database.
 * 没有写入磁盘，也没有写入数据库。
 */
public final class PageDefinitionRejected extends IllegalArgumentException {

    public PageDefinitionRejected(String message) {
        super(message);
    }
}
