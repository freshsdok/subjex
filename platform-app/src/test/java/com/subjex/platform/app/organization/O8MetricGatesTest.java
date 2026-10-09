package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O8-6 metric gates for Organization model migration FULL PASS (source scans).
 * Exceptions: {@code org.legacy}, {@code db.migration}, {@code OrganizationOntologyBackfill}.
 */
class O8MetricGatesTest {

    private static final List<String> FORMAL_PACKAGES =
            List.of("organization", "security", "form", "entity", "declaration", "api", "web");

    private static final Pattern MAP_SQL =
            Pattern.compile("org_unit_organization_map", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEGACY_TABLE_SQL =
            Pattern.compile(
                    "(?i)(FROM|INTO|UPDATE|JOIN|TABLE)\\s+org_(unit|membership)\\b");

    private static final Pattern FORBIDDEN_IDENTIFIERS =
            Pattern.compile("\\b(orgUnitId|rootUnitIds|unitIds)\\b");

    @Test
    @DisplayName("O8-METRIC-01 Production ontology count = 1 (OrgUnit only under org.legacy)")
    void O8_METRIC_01_productionOntologyCountIsOne() throws IOException {
        Path main = resolveMainApp();
        List<Path> orgUnitFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(main)) {
            walk.filter(p -> p.getFileName().toString().equals("OrgUnit.java")
                            || p.getFileName().toString().equals("OrgMembership.java"))
                    .forEach(orgUnitFiles::add);
        }
        assertFalse(orgUnitFiles.isEmpty(), "expected OrgUnit/OrgMembership under org.legacy");
        for (Path p : orgUnitFiles) {
            String rel = main.relativize(p).toString().replace('\\', '/');
            assertTrue(
                    rel.startsWith("org/legacy/"),
                    () -> "OrgUnit/OrgMembership must live under org.legacy, found " + rel);
        }
    }

    @Test
    @DisplayName("O8-METRIC-02 Legacy domain consumers = 0 outside org.legacy")
    void O8_METRIC_02_legacyDomainConsumersZeroOutsideLegacy() throws IOException {
        Path main = resolveMainApp();
        List<String> hits = new ArrayList<>();
        for (String pkg : FORMAL_PACKAGES) {
            Path dir = main.resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String text = Files.readString(p);
                        if (text.contains("import com.subjex.platform.app.org.legacy.")) {
                            hits.add(main.relativize(p) + " imports org.legacy");
                        }
                        if (text.contains("import com.subjex.platform.app.org.legacy.OrgUnit")
                                || text.contains("import com.subjex.platform.app.org.legacy.OrgMembership")
                                || text.contains("new OrgUnit(")
                                || text.contains("new OrgMembership(")) {
                            hits.add(main.relativize(p).toString());
                        }
                    } catch (IOException e) {
                        fail(p + ": " + e.getMessage());
                    }
                });
            }
        }
        assertTrue(hits.isEmpty(), () -> "legacy domain consumers outside org.legacy: " + hits);
    }

    @Test
    @DisplayName("O8-METRIC-03 Legacy SQL consumers = 0 in formal/policy/new API")
    void O8_METRIC_03_legacySqlConsumersZeroFormal() throws IOException {
        Path main = resolveMainApp();
        List<String> hits = new ArrayList<>();
        for (String pkg : List.of("security", "form", "entity", "declaration", "api", "web")) {
            scanSql(main.resolve(pkg), main, hits);
        }
        Path orgDir = main.resolve("organization");
        if (Files.isDirectory(orgDir)) {
            try (Stream<Path> walk = Files.walk(orgDir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    if (p.getFileName().toString().equals("OrganizationOntologyBackfill.java")) {
                        return;
                    }
                    try {
                        String text = Files.readString(p);
                        if (MAP_SQL.matcher(text).find() && !isJavadocOnlyMapMention(text)) {
                            hits.add(main.relativize(p) + " map SQL");
                        }
                        if (LEGACY_TABLE_SQL.matcher(text).find()) {
                            hits.add(main.relativize(p) + " legacy table SQL");
                        }
                    } catch (IOException e) {
                        fail(p + ": " + e.getMessage());
                    }
                });
            }
        }
        assertTrue(hits.isEmpty(), () -> "formal legacy SQL consumers: " + hits);
    }

    @Test
    @DisplayName("O8-METRIC-04 Policy legacy dependency = 0")
    void O8_METRIC_04_policyLegacyDependencyZero() throws IOException {
        Path security = resolveMainApp().resolve("security");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(security)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p);
                    assertFalse(
                            text.contains("import com.subjex.platform.app.org.legacy."),
                            () -> p + " imports org.legacy");
                    if (FORBIDDEN_IDENTIFIERS.matcher(text).find()) {
                        hits.add(resolveMainApp().relativize(p).toString());
                    }
                    if (text.contains("ATTR_ORG_UNIT_ID")) {
                        hits.add(resolveMainApp().relativize(p) + " ATTR_ORG_UNIT_ID");
                    }
                } catch (IOException e) {
                    fail(p + ": " + e.getMessage());
                }
            });
        }
        assertTrue(hits.isEmpty(), () -> "policy legacy identifiers: " + hits);
    }

    @Test
    @DisplayName("O8-METRIC-05 Zero-code legacy refs = 0 (aliases rejected)")
    void O8_METRIC_05_zeroCodeLegacyRefsZero() throws IOException {
        Path root = resolveRepoRoot();
        assertFalse(
                Files.exists(root.resolve("web/src/components/page-blocks/user-picker.tsx")),
                "user-picker.tsx must be deleted");
        assertFalse(
                Files.exists(root.resolve("web/src/components/page-blocks/org-picker.tsx")),
                "org-picker.tsx must be deleted");

        List<String> hits = new ArrayList<>();
        for (Path file : List.of(
                root.resolve("web/src/lib/form-wizard.ts"),
                root.resolve("web/src/lib/entity-wizard.ts"),
                root.resolve("web/src/lib/form-field-input.ts"),
                root.resolve("web/src/lib/page-blocks-catalog.ts"))) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String text = Files.readString(file);
            if (text.contains("if (raw === \"userRef\")")
                    || text.contains("if (raw === \"orgRef\")")
                    || text.contains("LEGACY_PAGE_BLOCK_ALIASES")
                    || text.contains("case \"userRef\"")
                    || text.contains("case \"orgRef\"")) {
                hits.add(file.toString());
            }
        }
        // Parsers must reject, not accept
        for (Path file : List.of(
                root.resolve(
                        "entity-declare/src/main/java/com/subjex/entity/declare/EntityFieldKind.java"),
                root.resolve("form-render/src/main/java/com/subjex/form/render/FieldKind.java"))) {
            String text = Files.readString(file);
            assertTrue(
                    text.contains("is not allowed") || text.contains("not allowed"),
                    () -> file + " must reject legacy aliases");
            assertFalse(
                    text.contains("return SUBJECT_REF") && text.contains("\"userRef\".equals")
                            && !text.contains("throw"),
                    () -> file + " must not dual-accept userRef");
        }
        assertTrue(hits.isEmpty(), () -> "zero-code alias accept residual: " + hits);
    }

    @Test
    @DisplayName("O8-ARCH-02 New packages do not import org.legacy (extended)")
    void O8_ARCH_02_newPackagesDoNotImportOrgLegacy() throws IOException {
        Path main = resolveMainApp();
        for (String pkg : FORMAL_PACKAGES) {
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
                    } catch (IOException e) {
                        fail(p + ": " + e.getMessage());
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("O8-MAP-01 Formal runtime has no org_unit_organization_map SQL")
    void O8_MAP_01_formalRuntimeNoMapSql() throws IOException {
        Path main = resolveMainApp();
        List<String> hits = new ArrayList<>();
        for (String pkg : List.of("security", "form", "api", "web", "declaration", "entity")) {
            scanMap(main.resolve(pkg), main, hits);
        }
        for (String name : List.of(
                "organization/OrganizationApiEndpoint.java",
                "organization/OrganizationScopeResolver.java")) {
            Path p = main.resolve(name);
            if (Files.isRegularFile(p)) {
                String text = Files.readString(p);
                String withoutComments =
                        text.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
                if (MAP_SQL.matcher(withoutComments).find()) {
                    hits.add(name);
                }
            }
        }
        assertTrue(hits.isEmpty(), () -> "formal map SQL: " + hits);
    }

    private static void scanSql(Path dir, Path main, List<String> hits) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p);
                    if (MAP_SQL.matcher(text).find() && !isJavadocOnlyMapMention(text)) {
                        hits.add(main.relativize(p) + " map");
                    }
                    if (LEGACY_TABLE_SQL.matcher(text).find()) {
                        hits.add(main.relativize(p) + " legacy table");
                    }
                } catch (IOException e) {
                    fail(p + ": " + e.getMessage());
                }
            });
        }
    }

    private static void scanMap(Path dir, Path main, List<String> hits) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p);
                    if (MAP_SQL.matcher(text).find() && !isJavadocOnlyMapMention(text)) {
                        hits.add(main.relativize(p).toString());
                    }
                } catch (IOException e) {
                    fail(p + ": " + e.getMessage());
                }
            });
        }
    }

    private static boolean isJavadocOnlyMapMention(String text) {
        String withoutComments =
                text.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
        return !MAP_SQL.matcher(withoutComments).find();
    }

    private static Path resolveMainApp() {
        Path main = Path.of("src/main/java/com/subjex/platform/app");
        if (Files.isDirectory(main)) {
            return main;
        }
        main = Path.of("platform-app/src/main/java/com/subjex/platform/app");
        if (Files.isDirectory(main)) {
            return main;
        }
        fail("cannot locate main sources under " + Path.of("").toAbsolutePath());
        return main;
    }

    private static Path resolveRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("platform-app"))
                    && Files.isDirectory(dir.resolve("web"))) {
                return dir;
            }
        }
        return cwd;
    }
}
