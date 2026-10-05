import { describe, expect, it, vi } from "vitest";

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));
vi.mock("next/navigation", () => ({ redirect: () => { throw new Error("redirect"); } }));

const { skinStyleFor } = await import("@/server/skin");

describe("skin mapping — 皮肤变量映射", () => {
  it("maps platform variables onto console variables — 平台变量映射到控制台变量", () => {
    expect(
      skinStyleFor({ "--page-background": "#e7eef2", "--page-text": "#1a2a32", "--page-muted": "#3d5560", "--page-line": "#b7c6ce", "--unknown": "#000" }),
    ).toEqual({ "--background": "#e7eef2", "--foreground": "#1a2a32", "--muted": "#3d5560", "--border": "#b7c6ce", "--surface": "#e7eef2" });
  });

  it("returns nothing when no skin is chosen — 未选皮肤时不覆盖", () => {
    expect(skinStyleFor(undefined)).toEqual({});
  });
});
