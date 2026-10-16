package com.subjex.platform.app.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** FeatureFlagsEndpointTest — boolean parse delegate. */
class FeatureFlagsEndpointTest {

    @Test
    void parseEnabledRecognizesCommonTokens() {
        assertTrue(FeatureFlagsEndpoint.parseEnabled("true"));
        assertTrue(FeatureFlagsEndpoint.parseEnabled("YES"));
        assertEquals(Boolean.FALSE, FeatureFlagsEndpoint.parseEnabled("0"));
        assertEquals(Boolean.FALSE, FeatureFlagsEndpoint.parseEnabled("off"));
        assertNull(FeatureFlagsEndpoint.parseEnabled("maybe"));
        assertNull(FeatureFlagsEndpoint.parseEnabled(""));
    }
}
