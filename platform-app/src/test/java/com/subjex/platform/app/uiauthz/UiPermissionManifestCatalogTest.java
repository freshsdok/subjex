package com.subjex.platform.app.uiauthz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * UiPermissionManifestCatalogTest — PC-1：classpath 加载；侧栏等价；未知权限失败关闭。
 * <p>
 * Loads console baseline; menu href/permission match {@code console-sections.ts}; invent refuse.
 * 加载控制台基线；菜单与 console-sections 等价；自造权限拒绝。
 */
class UiPermissionManifestCatalogTest {

    private final UiPermissionManifestCatalog catalog = new UiPermissionManifestCatalog();

    @Test
    void loadsClasspathConsoleBaseline() {
        UiPermissionManifest manifest = catalog.require(UiPermissionManifest.CLIENT_CONSOLE);
        assertEquals(1, manifest.version());
        assertEquals(UiPermissionManifest.CLIENT_CONSOLE, manifest.client());
        assertEquals(UiPermissionManifestDefaults.EFFECT_GREY, manifest.defaults().lockedMenuEffect());
        assertEquals(
                UiPermissionManifestDefaults.EFFECT_DISABLE, manifest.defaults().missingButtonEffect());
        assertEquals(20, manifest.menus().size());
        assertTrue(catalog.knows("console"));
        assertTrue(catalog.knows("uni-app"));
        assertEquals(2, catalog.list().size());
    }

    @Test
    void menuBindingsMatchConsoleSectionsSidebar() {
        List<UiPermissionEntry> menus = catalog.require("console").menus();
        // Order + href + permission/anyOf must match web/.../console-sections.ts
        assertMenu(menus.get(0), "nav.overview", "/", null, List.of());
        assertMenu(menus.get(1), "nav.services", "/services", "registry.read", List.of());
        assertMenu(menus.get(2), "nav.config", "/config", "config.read", List.of());
        assertMenu(menus.get(3), "nav.flags", "/flags", "config.read", List.of());
        assertMenu(menus.get(4), "nav.inbox", "/inbox", "inbox.read", List.of());
        assertMenu(
                menus.get(5),
                "nav.inbox.compose",
                "/inbox/compose",
                "inbox.send.system",
                List.of("inbox.send.system", "inbox.broadcast"));
        assertMenu(menus.get(6), "nav.inbox.groups", "/inbox/groups", "inbox.group.admin", List.of());
        assertMenu(menus.get(7), "nav.audit", "/audit", "admin.read", List.of());
        assertMenu(menus.get(8), "nav.dead-letters", "/dead-letters", "admin.read", List.of());
        assertMenu(menus.get(9), "nav.deploy", "/deploy", "page.read", List.of());
        assertMenu(menus.get(10), "nav.forms", "/forms", "page.read", List.of());
        assertMenu(menus.get(11), "nav.pages", "/pages", "page.read", List.of());
        assertMenu(menus.get(12), "nav.search", "/search", "page.read", List.of());
        assertMenu(menus.get(13), "nav.declarations", "/declarations", "declaration.read", List.of());
        assertMenu(menus.get(14), "nav.codegen", "/codegen", "page.read", List.of());
        assertMenu(menus.get(15), "nav.operators", "/operators", null, List.of());
        assertMenu(menus.get(16), "nav.tenants", "/tenants", "admin.read", List.of());
        assertMenu(menus.get(17), "nav.org", "/org", "org.read", List.of());
        assertMenu(menus.get(18), "nav.capabilities", "/capabilities", "page.read", List.of());
        assertMenu(menus.get(19), "nav.ui-authz", "/ui-authz", "declaration.read", List.of());
        assertTrue(menus.get(0).loginOnly());
        assertTrue(menus.get(15).loginOnly());
    }

    @Test
    void unknownPermissionIsRejected() {
        String yaml = """
                version: 1
                client: console
                defaults:
                  lockedMenuEffect: grey
                  missingButtonEffect: disable
                entries:
                  - id: nav.evil
                    kind: menu
                    href: /evil
                    permission: not.a.real.permission
                """;
        UiPermissionRejected ex =
                assertThrows(UiPermissionRejected.class, () -> UiPermissionManifestCatalog.parseYaml(yaml));
        assertTrue(ex.getMessage().contains("unknown permission"));
        assertTrue(ex.getMessage().contains("not.a.real.permission"));
    }

    @Test
    void unknownAnyOfPermissionIsRejected() {
        String yaml = """
                version: 1
                client: console
                defaults:
                  lockedMenuEffect: grey
                  missingButtonEffect: disable
                entries:
                  - id: nav.evil
                    kind: menu
                    href: /evil
                    anyOf:
                      - page.read
                      - invented.write
                """;
        UiPermissionRejected ex =
                assertThrows(UiPermissionRejected.class, () -> UiPermissionManifestCatalog.parseYaml(yaml));
        assertTrue(ex.getMessage().contains("invented.write"));
    }

    @Test
    void orgScopeKeyIsRejectedInWave1() {
        String yaml = """
                version: 1
                client: console
                defaults:
                  lockedMenuEffect: grey
                  missingButtonEffect: disable
                entries:
                  - id: nav.org
                    kind: menu
                    href: /org
                    permission: org.read
                    orgScopeMode: SELF_AND_DESCENDANTS
                """;
        assertThrows(UiPermissionRejected.class, () -> UiPermissionManifestCatalog.parseYaml(yaml));
    }

    @Test
    void duplicateIdIsRejected() {
        String yaml = """
                version: 1
                client: console
                entries:
                  - id: nav.a
                    kind: menu
                    href: /a
                    permission: page.read
                  - id: nav.a
                    kind: menu
                    href: /b
                    permission: page.read
                """;
        assertThrows(UiPermissionRejected.class, () -> UiPermissionManifestCatalog.parseYaml(yaml));
    }

    @Test
    void packagePrivateCtorRequiresMatchingClientKey() {
        String yaml = """
                version: 1
                client: console
                entries:
                  - id: nav.overview
                    kind: menu
                    href: /
                """;
        assertThrows(
                UiPermissionRejected.class,
                () -> new UiPermissionManifestCatalog(Map.of("uni-app", yaml)));
    }

    private static void assertMenu(
            UiPermissionEntry entry,
            String id,
            String href,
            String permission,
            List<String> anyOf) {
        assertEquals(UiPermissionEntryKind.MENU, entry.kind());
        assertEquals(id, entry.id());
        assertEquals(href, entry.href());
        assertEquals(permission, entry.permission());
        assertEquals(anyOf, entry.anyOf());
    }
}
