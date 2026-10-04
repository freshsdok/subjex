package com.subjex.platform.contract.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConnectionVendorTest {

    @Test
    void productNamesMapToTheTwoSupportedVendors() {
        assertEquals(ConnectionVendor.MYSQL, ConnectionVendor.fromProductName("MySQL"));
        assertEquals(ConnectionVendor.POSTGRESQL, ConnectionVendor.fromProductName("PostgreSQL"));
        assertEquals(ConnectionVendor.MYSQL, ConnectionVendor.parse("mysql"));
        assertEquals(ConnectionVendor.POSTGRESQL, ConnectionVendor.parse("postgresql"));
    }

    @Test
    void otherProductsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionVendor.fromProductName("H2"));
        assertThrows(IllegalArgumentException.class, () -> ConnectionVendor.parse(""));
    }
}
