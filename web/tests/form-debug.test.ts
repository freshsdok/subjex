import { describe, expect, it } from "vitest";
import { parseSubmitReply } from "@/lib/form-debug";

describe("form-debug helpers — 表单调试辅助", () => {
  it("maps success body to persist_ok — 成功体映射为落库成功", () => {
    const state = parseSubmitReply(200, {
      submissionId: "sub-1",
      formKey: "endpoint-publication",
      declarationVersion: 1,
      resultSummary: "billing@10.0.0.8:8080",
      submittedAt: "2026-10-05T07:00:00Z",
      effects: [
        { key: "audit.write", outcome: "ok" },
        { key: "task.enqueue", outcome: "ok" },
      ],
    });
    expect(state.kind).toBe("persist_ok");
    if (state.kind !== "persist_ok") return;
    expect(state.persist.submissionId).toBe("sub-1");
    expect(state.persist.declarationVersion).toBe(1);
    expect(state.persist.effects).toEqual([
      { key: "audit.write", outcome: "ok" },
      { key: "task.enqueue", outcome: "ok" },
    ]);
  });

  it("maps validation problem — 映射校验失败", () => {
    const state = parseSubmitReply(400, {
      kind: "validation",
      fieldErrors: [{ field: "port", code: "required", message: "field port is required" }],
      message: "field port is required",
    });
    expect(state).toEqual({
      kind: "validation",
      fieldErrors: [{ field: "port", code: "required", message: "field port is required" }],
      message: "field port is required",
    });
  });

  it("maps permission_denied — 映射权限拒绝", () => {
    const state = parseSubmitReply(403, {
      kind: "permission_denied",
      permission: "registry.write",
      message: "registry.write required",
    });
    expect(state).toEqual({
      kind: "permission_denied",
      permission: "registry.write",
      message: "registry.write required",
    });
  });

  it("falls back for unknown shapes — 未知形状回退", () => {
    expect(parseSubmitReply(500, { reason: "boom" })).toEqual({
      kind: "unknown",
      status: 500,
      message: "boom",
    });
    expect(parseSubmitReply(502, null)).toEqual({ kind: "unknown", status: 502, message: "" });
  });
});
