import { describe, expect, it } from "vitest";
import { consoleSections } from "@/app/(console)/console-sections";
import {
  BUTTON_INBOX_COMPOSE_SEND,
  BUTTON_ORG_WRITE,
  buildConsoleNavItems,
  buttonAllowed,
  pageAllowed,
  PAGE_ORG,
  PAGE_INBOX,
  CONSOLE_MENU_LABEL_BY_ID,
  missingPermissionHint,
  resolveConsoleMenuLabelKey,
  type UiPermissionManifestDocument,
} from "@/lib/ui-permissions";

describe("ui-permissions console nav (PC-3/PC-4)", () => {
  it("maps every classpath menu id to a phrase key", () => {
    expect(Object.keys(CONSOLE_MENU_LABEL_BY_ID)).toHaveLength(consoleSections.length);
    for (const section of consoleSections) {
      const id = Object.entries(CONSOLE_MENU_LABEL_BY_ID).find(([, key]) => key === section.labelKey)?.[0];
      expect(id, section.labelKey).toBeTruthy();
    }
  });

  it("resolves label by id then href fallback", () => {
    expect(resolveConsoleMenuLabelKey("nav.org", "/org", consoleSections)).toBe("navOrg");
    expect(resolveConsoleMenuLabelKey("unknown.id", "/org", consoleSections)).toBe("navOrg");
    expect(resolveConsoleMenuLabelKey("unknown.id", "/nope", consoleSections)).toBeUndefined();
  });

  it("builds missing permission hint from anyOf or permission", () => {
    expect(
      missingPermissionHint({
        id: "x",
        kind: "menu",
        anyOf: ["inbox.send.system", "inbox.broadcast"],
        allowed: false,
      }),
    ).toBe("inbox.send.system | inbox.broadcast");
    expect(
      missingPermissionHint({ id: "x", kind: "menu", permission: "org.read", allowed: false }),
    ).toBe("org.read");
  });

  it("uses Manifest allowed flags as gate source", () => {
    const manifest: UiPermissionManifestDocument = {
      version: 1,
      client: "console",
      entries: [
        { id: "nav.overview", kind: "menu", href: "/", allowed: true },
        {
          id: "nav.inbox.compose",
          kind: "menu",
          href: "/inbox/compose",
          permission: "inbox.send.system",
          anyOf: ["inbox.send.system", "inbox.broadcast"],
          allowed: false,
        },
        { id: "nav.org", kind: "menu", href: "/org", permission: "org.read", allowed: true },
      ],
    };
    const items = buildConsoleNavItems(manifest, consoleSections, new Set());
    expect(items).toHaveLength(3);
    expect(items[0]).toMatchObject({ href: "/", labelKey: "navOverview", allowed: true });
    expect(items[1]).toMatchObject({
      href: "/inbox/compose",
      labelKey: "navInboxCompose",
      allowed: false,
      missingPermissionHint: "inbox.send.system | inbox.broadcast",
    });
    expect(items[2].labelKey).toBe("navOrg");
  });

  it("falls back to console-sections when Manifest missing", () => {
    const items = buildConsoleNavItems(undefined, consoleSections, new Set(["org.read"]));
    expect(items).toHaveLength(consoleSections.length);
    const org = items.find((i) => i.href === "/org");
    expect(org?.allowed).toBe(true);
    const compose = items.find((i) => i.href === "/inbox/compose");
    expect(compose?.allowed).toBe(false);
  });

  it("buttonAllowed uses Manifest allowed when button present", () => {
    const manifest: UiPermissionManifestDocument = {
      version: 1,
      client: "console",
      entries: [
        {
          id: BUTTON_ORG_WRITE,
          kind: "button",
          permission: "org.write",
          allowed: false,
        },
        {
          id: BUTTON_INBOX_COMPOSE_SEND,
          kind: "button",
          anyOf: ["inbox.send.system", "inbox.broadcast"],
          allowed: true,
        },
      ],
    };
    expect(buttonAllowed(manifest, BUTTON_ORG_WRITE, true)).toBe(false);
    expect(buttonAllowed(manifest, BUTTON_INBOX_COMPOSE_SEND, false)).toBe(true);
    expect(buttonAllowed(undefined, BUTTON_ORG_WRITE, true)).toBe(true);
  });

  it("pageAllowed uses Manifest allowed when page present", () => {
    const manifest: UiPermissionManifestDocument = {
      version: 1,
      client: "console",
      entries: [
        { id: PAGE_ORG, kind: "page", route: "/org", permission: "org.read", allowed: false },
        { id: PAGE_INBOX, kind: "page", route: "/inbox", permission: "inbox.read", allowed: true },
      ],
    };
    expect(pageAllowed(manifest, PAGE_ORG, true)).toBe(false);
    expect(pageAllowed(manifest, PAGE_INBOX, false)).toBe(true);
    expect(pageAllowed(undefined, PAGE_ORG, true)).toBe(true);
  });
});
