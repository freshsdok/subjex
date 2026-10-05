import { describe, expect, it } from "vitest";
import { fillPhrase, phrasesFor, pickLanguage } from "@/i18n/phrases";

describe("phrases — 文案", () => {
  it("defaults to Chinese unless the cookie says en — 默认中文", () => {
    expect(pickLanguage(undefined)).toBe("zh");
    expect(pickLanguage("fr")).toBe("zh");
    expect(pickLanguage("en")).toBe("en");
  });

  it("has the same keys in both languages — 中英键一致", () => {
    expect(Object.keys(phrasesFor("en")).sort()).toEqual(Object.keys(phrasesFor("zh")).sort());
  });

  it("fills known placeholders and keeps unknown ones — 替换已知占位符", () => {
    expect(fillPhrase("需要权限 {permission}", { permission: "config.write" })).toBe("需要权限 config.write");
    expect(fillPhrase("{known} {unknown}", { known: 1 })).toBe("1 {unknown}");
  });
});
