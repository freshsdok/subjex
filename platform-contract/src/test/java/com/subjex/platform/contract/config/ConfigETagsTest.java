package com.subjex.platform.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigETagsTest {

    @Test
    void quotesRevisionAndParsesHeaders() {
        assertEquals("\"3\"", ConfigETags.ofRevision(3));
        assertEquals(3L, ConfigETags.parseOne("\"3\"").getAsLong());
        assertEquals(3L, ConfigETags.parseOne("W/\"3\"").getAsLong());
        assertTrue(ConfigETags.noneMatchHits("\"2\", \"3\"", 3));
        assertFalse(ConfigETags.noneMatchHits("\"2\"", 3));
        assertTrue(ConfigETags.matchAllows(null, 1));
        assertTrue(ConfigETags.matchAllows("\"1\"", 1));
        assertFalse(ConfigETags.matchAllows("\"2\"", 1));
        assertTrue(ConfigETags.matchAllows("*", 0));
    }
}
