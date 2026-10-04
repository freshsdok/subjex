package com.subjex.platform.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Both drivers are on the classpath even when no live database is running.
 * 即使没有正在运行的数据库，两个驱动也在 classpath 上。
 */
class DriverPresenceTest {

    @Test
    void mysqlAndPostgresqlDriversAreLoadable() throws Exception {
        assertNotNull(Class.forName("com.mysql.cj.jdbc.Driver"));
        assertNotNull(Class.forName("org.postgresql.Driver"));
    }
}
