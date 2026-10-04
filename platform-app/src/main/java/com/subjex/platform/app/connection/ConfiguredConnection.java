package com.subjex.platform.app.connection;

import com.subjex.platform.contract.connection.ConnectionVendor;
import com.subjex.platform.contract.connection.RelationalConnectionPort;
import javax.sql.DataSource;

/**
 * ConfiguredConnection — 已配置连接：本进程选定的厂商，加上 Spring 配好的 JDBC 连接。
 * <p>
 * Swapping MySQL and PostgreSQL is a configuration change. This class does not parse rows into an entity model.
 * 切换 MySQL 与 PostgreSQL 只改配置。这个类不把行解析成实体模型。
 */
public final class ConfiguredConnection implements RelationalConnectionPort {

    private final ConnectionVendor vendor;
    private final DataSource source;

    public ConfiguredConnection(ConnectionVendor vendor, DataSource source) {
        this.vendor = vendor;
        this.source = source;
    }

    @Override
    public ConnectionVendor vendor() {
        return vendor;
    }

    @Override
    public DataSource connectionSource() {
        return source;
    }
}
