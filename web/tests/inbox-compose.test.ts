import { describe, expect, it } from "vitest";
import {
  availableComposeModes,
  canOpenCompose,
  defaultComposeMode,
  INBOX_ATTACH_MAX_BYTES,
  INBOX_ATTACH_MAX_FILES,
  isAllowedInboxAttachmentType,
  parseIdList,
  validateComposeDraft,
} from "@/lib/inbox-compose";

describe("inbox-compose helpers (IM-A7)", () => {
  it("parses and dedupes id lists", () => {
    expect(parseIdList(" a,b\nc ;a ")).toEqual(["a", "b", "c"]);
    expect(parseIdList("")).toEqual([]);
  });

  it("exposes attachment caps aligned with ADR 0009", () => {
    expect(INBOX_ATTACH_MAX_FILES).toBe(5);
    expect(INBOX_ATTACH_MAX_BYTES).toBe(5 * 1024 * 1024);
  });

  it("allowlists attachment MIME types", () => {
    expect(isAllowedInboxAttachmentType("application/pdf")).toBe(true);
    expect(isAllowedInboxAttachmentType("image/png")).toBe(true);
    expect(isAllowedInboxAttachmentType("application/zip")).toBe(false);
    expect(isAllowedInboxAttachmentType("")).toBe(false);
  });

  it("gates compose modes by permission", () => {
    expect(availableComposeModes([])).toEqual([]);
    expect(canOpenCompose([])).toBe(false);
    expect(availableComposeModes(["inbox.send.system"])).toEqual(["system"]);
    expect(availableComposeModes(["inbox.broadcast"])).toEqual(["group", "org"]);
    expect(availableComposeModes(["inbox.send.system", "inbox.broadcast"])).toEqual([
      "system",
      "group",
      "org",
    ]);
    expect(defaultComposeMode(["group", "org"])).toBe("group");
    expect(defaultComposeMode(["system", "group"])).toBe("system");
  });

  it("validates compose drafts", () => {
    const base = {
      mode: "system" as const,
      allowedModes: ["system" as const],
      tenantId: "t1",
      title: "hi",
      body: "body",
      recipientSubjectIds: ["op-1"],
      groupId: "",
      orgIds: [] as string[],
      attachmentCount: 0,
    };
    expect(validateComposeDraft(base)).toBeNull();
    expect(validateComposeDraft({ ...base, tenantId: "" })).toBe("needTenant");
    expect(validateComposeDraft({ ...base, recipientSubjectIds: [] })).toBe("needRecipients");
    expect(
      validateComposeDraft({
        ...base,
        mode: "group",
        allowedModes: ["group"],
        groupId: "",
      }),
    ).toBe("needGroup");
    expect(
      validateComposeDraft({
        ...base,
        mode: "org",
        allowedModes: ["org"],
        orgIds: [],
      }),
    ).toBe("needOrgs");
    expect(validateComposeDraft({ ...base, attachmentCount: 6 })).toBe("tooManyAttachments");
    expect(validateComposeDraft({ ...base, mode: "group", allowedModes: ["system"] })).toBe(
      "modeForbidden",
    );
  });
});
