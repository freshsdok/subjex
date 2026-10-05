package com.subjex.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NamedSkinTest {

    @Test
    void queryWinsOverSavedAndUnknownIsPlain() {
        assertEquals(NamedSkin.PLAIN, NamedSkin.choose(null, null));
        assertEquals(NamedSkin.CALM, NamedSkin.choose(null, "calm"));
        assertEquals(NamedSkin.HIGH_CONTRAST, NamedSkin.choose("high-contrast", "calm"));
        assertEquals(NamedSkin.CALM, NamedSkin.choose("neon", "calm"));
        assertEquals(NamedSkin.PLAIN, NamedSkin.choose("neon", "nope"));
        assertNull(NamedSkin.parse(""));
        assertEquals("朴素", NamedSkin.PLAIN.chinese());
        assertEquals("high contrast", NamedSkin.HIGH_CONTRAST.english());
        assertEquals("沉静", NamedSkin.CALM.chinese());
    }

    @Test
    void styleSheetIsThreeNamesAndFourVariables() {
        String css = NamedSkin.styleSheet();
        assertTrue(css.contains("html[data-skin=\"plain\"]"));
        assertTrue(css.contains("html[data-skin=\"high-contrast\"]"));
        assertTrue(css.contains("html[data-skin=\"calm\"]"));
        assertTrue(css.contains("--page-background"));
        assertTrue(css.contains("--page-text"));
        assertTrue(css.contains("--page-muted"));
        assertTrue(css.contains("--page-line"));
        assertFalse(css.contains("--page-accent"));
        assertEquals(4, count(css, "--page-background"));
        assertFalse(css.contains("picker"));
    }

    private static int count(String text, String piece) {
        int found = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(piece, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + piece.length();
        }
    }
}
