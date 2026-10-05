package com.subjex.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubjexInitMainTest {

    @TempDir
    Path temp;

    @Test
    void skipFlagWritesNothing() throws Exception {
        Path dir = temp.resolve("empty");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code =
                SubjexInitMain.run(
                        new String[] {"--skip", "--dir", dir.toString()},
                        LineSource.of(() -> ""),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertFalse(Files.exists(dir));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("skipped"));
    }

    @Test
    void yesWritesDefaults() throws Exception {
        Path dir = temp.resolve("yes-out");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code =
                SubjexInitMain.run(
                        new String[] {"--yes", "--dir", dir.toString()},
                        LineSource.of(() -> {
                            throw new AssertionError("should not read stdin");
                        }),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertTrue(Files.isRegularFile(dir.resolve(".env.example")));
        assertTrue(Files.isRegularFile(dir.resolve("application-local.yml")));
        assertTrue(Files.isRegularFile(dir.resolve("INIT-NOTES.md")));
    }

    @Test
    void interactiveSkipAtFirstPrompt() throws Exception {
        Path dir = temp.resolve("interactive-skip");
        Deque<String> answers = new ArrayDeque<>(List.of("skip"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code =
                SubjexInitMain.run(
                        new String[] {"--dir", dir.toString()},
                        () -> answers.isEmpty() ? "" : answers.removeFirst(),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertFalse(Files.exists(dir));
    }

    @Test
    void interactiveAcceptsDefaults() throws Exception {
        Path dir = temp.resolve("interactive-ok");
        // blank lines → defaults for dir/vendor/url/user/pass/redis
        Deque<String> answers = new ArrayDeque<>(List.of("", "", "", "", "", ""));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code =
                SubjexInitMain.run(
                        new String[] {"--dir", dir.toString()},
                        () -> answers.isEmpty() ? "" : answers.removeFirst(),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertTrue(Files.isRegularFile(dir.resolve(".env.example")));
    }

    @Test
    void helpExitsZero() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code =
                SubjexInitMain.run(
                        new String[] {"--help"},
                        LineSource.of(() -> ""),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("--yes"));
    }
}
