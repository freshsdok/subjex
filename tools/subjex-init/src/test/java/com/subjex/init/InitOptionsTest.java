package com.subjex.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InitOptionsTest {

    @Test
    void defaultsWhenNoArgs() {
        InitOptions o = InitOptions.parse(new String[] {});
        assertEquals(InitOptions.DEFAULT_DIR, o.targetDir());
        assertFalse(o.yes());
        assertFalse(o.skip());
        assertFalse(o.help());
    }

    @Test
    void parsesYesDirSkipHelp() {
        InitOptions o = InitOptions.parse(new String[] {"--yes", "--dir", "out", "--skip", "--help"});
        assertTrue(o.yes());
        assertTrue(o.skip());
        assertTrue(o.help());
        assertEquals(Path.of("out"), o.targetDir());
    }

    @Test
    void rejectsUnknownFlag() {
        assertThrows(IllegalArgumentException.class, () -> InitOptions.parse(new String[] {"--nope"}));
    }

    @Test
    void skipTokens() {
        assertTrue(InitOptions.isSkipToken("skip"));
        assertTrue(InitOptions.isSkipToken(" SKIP "));
        assertTrue(InitOptions.isSkipToken("s"));
        assertFalse(InitOptions.isSkipToken("subjex-dev"));
    }
}
