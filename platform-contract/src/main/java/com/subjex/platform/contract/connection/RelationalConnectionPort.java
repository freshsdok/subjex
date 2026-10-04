package com.subjex.platform.contract.connection;

import javax.sql.DataSource;

/**
 * RelationalConnectionPort — 关系库连接端口：当前厂商与可替换的 JDBC 连接。
 * <p>
 * Callers write SQL through this connection. There is no session, entity manager, or repository model on the path.
 * 调用方通过这条连接写 SQL。路径上没有会话、实体管理器或仓储模型。
 */
public interface RelationalConnectionPort {

    ConnectionVendor vendor();

    /**
     * JDBC connection source selected for this process — 本进程选中的 JDBC 连接源。
     */
    DataSource connectionSource();
}
