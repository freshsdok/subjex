import { describe, expect, it } from "vitest";
import {
  formatInboxUnreadCount,
  INBOX_UNREAD_POLL_MS,
  resolveInboxTenantFromCookieHeader,
  resolveInboxTenantId,
} from "@/lib/inbox-console";

describe("inbox-console helpers (IM-5)", () => {
  it("uses locked 60s poll interval", () => {
    expect(INBOX_UNREAD_POLL_MS).toBe(60_000);
  });

  it("prefers org tenant cookie over declaration", () => {
    const header =
      "subjex_declaration_tenant=decl-tenant; subjex_org_tenant=org-tenant; other=x";
    expect(resolveInboxTenantFromCookieHeader(header)).toBe("org-tenant");
  });

  it("falls back to declaration then initial hint", () => {
    expect(resolveInboxTenantFromCookieHeader("subjex_declaration_tenant=only-decl")).toBe(
      "only-decl",
    );
    expect(resolveInboxTenantId("hint-tenant", "")).toBe("hint-tenant");
    expect(resolveInboxTenantId("hint-tenant", "subjex_org_tenant=live")).toBe("live");
  });

  it("formats badge count with 99+ cap", () => {
    expect(formatInboxUnreadCount(0)).toBe("");
    expect(formatInboxUnreadCount(3)).toBe("3");
    expect(formatInboxUnreadCount(99)).toBe("99");
    expect(formatInboxUnreadCount(100)).toBe("99+");
  });
});
