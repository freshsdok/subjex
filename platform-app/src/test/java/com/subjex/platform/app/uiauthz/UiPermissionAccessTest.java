package com.subjex.platform.app.uiauthz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * UiPermissionAccessTest — login-only / anyOf / single permission 判定。
 */
class UiPermissionAccessTest {

    @Test
    void loginOnlyAlwaysAllowed() {
        UiPermissionEntry entry =
                new UiPermissionEntry("nav.overview", UiPermissionEntryKind.MENU, "/", null, null, List.of());
        assertTrue(UiPermissionAccess.allowed(entry, Set.of()));
    }

    @Test
    void anyOfRequiresOneMatch() {
        UiPermissionEntry entry = new UiPermissionEntry(
                "nav.inbox.compose",
                UiPermissionEntryKind.MENU,
                "/inbox/compose",
                null,
                "inbox.send.system",
                List.of("inbox.send.system", "inbox.broadcast"));
        assertFalse(UiPermissionAccess.allowed(entry, Set.of("inbox.read")));
        assertTrue(UiPermissionAccess.allowed(entry, Set.of("inbox.broadcast")));
    }

    @Test
    void singlePermissionMembership() {
        UiPermissionEntry entry = new UiPermissionEntry(
                "nav.org", UiPermissionEntryKind.MENU, "/org", null, "org.read", List.of());
        assertFalse(UiPermissionAccess.allowed(entry, Set.of("page.read")));
        assertTrue(UiPermissionAccess.allowed(entry, Set.of("org.read")));
    }
}
