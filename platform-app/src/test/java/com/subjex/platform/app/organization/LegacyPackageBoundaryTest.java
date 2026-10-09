package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O8-3: New API / Policy packages must not import org.legacy (Legacy → New only).
 */
class LegacyPackageBoundaryTest {

    private static final List<String> PROTECTED = List.of("organization", "security", "form");

    @Test
    @DisplayName("O8-3 New packages do not import org.legacy")
    void newPackagesDoNotImportOrgLegacy() throws IOException {
        Path main = Path.of("src/main/java/com/subjex/platform/app");
        if (!Files.isDirectory(main)) {
            // surefire cwd may be module root
            main = Path.of("platform-app/src/main/java/com/subjex/platform/app");
        }
        if (!Files.isDirectory(main)) {
            fail("cannot locate main sources under " + Path.of("").toAbsolutePath());
        }
        for (String pkg : PROTECTED) {
            Path dir = main.resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String text = Files.readString(p);
                        assertFalse(
                                text.contains("import com.subjex.platform.app.org.legacy."),
                                () -> p + " imports org.legacy (New → Legacy forbidden)");
                        assertFalse(
                                text.contains("import com.subjex.platform.app.org.OrgUnit")
                                        || text.contains("import com.subjex.platform.app.org.OrgMembership")
                                        || text.contains("import com.subjex.platform.app.org.JdbcOrgDirectory")
                                        || text.contains("import com.subjex.platform.app.org.OrgApiEndpoint"),
                                () -> p + " imports pre-move org.* legacy types");
                    } catch (IOException e) {
                        fail(p + ": " + e.getMessage());
                    }
                });
            }
        }
    }
}
