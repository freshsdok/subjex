package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.connection.ConnectionVendor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * AttrsJsonbQueriesTest — purpose: SHARED-1 containment JSON + vendor gate for GIN @> path.
 * Gates: only PostgreSQL enables SQL containment; payload matches attrs field equality shape.
 * <p>
 * 目的：SHARED-1 包含 JSON 与厂商门闩。门禁：仅 PostgreSQL 启用 SQL 包含。
 */
class AttrsJsonbQueriesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sqlContainmentOnlyOnPostgresql() {
        assertTrue(AttrsJsonbQueries.sqlContainmentEnabled(ConnectionVendor.POSTGRESQL));
        assertFalse(AttrsJsonbQueries.sqlContainmentEnabled(ConnectionVendor.MYSQL));
    }

    @Test
    void equalityContainmentJsonEncodesScalars() {
        assertEquals(
                "{\"status\":\"open\"}",
                AttrsJsonbQueries.equalityContainmentJson("status", "open", json));
        assertEquals(
                "{\"priority\":3}",
                AttrsJsonbQueries.equalityContainmentJson("priority", 3, json));
        assertEquals(
                "{\"urgent\":true}",
                AttrsJsonbQueries.equalityContainmentJson("urgent", true, json));
    }

    @Test
    void attrsContainsSqlUsesCastBind() {
        // jsonb_path_ops GIN is designed for @> containment — jsonb_path_ops 面向 @> 包含
        assertEquals("attrs @> CAST(? AS jsonb)", AttrsJsonbQueries.ATTRS_CONTAINS);
    }
}
