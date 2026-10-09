import { describe, expect, it } from "vitest";
import {
  buildFormPayload,
  coerceFormFieldValue,
  emptyFieldValue,
  emptyFieldValues,
  fieldControlKind,
  fieldKindLabel,
  fieldPickerRole,
  isRequiredFieldMissing,
  type FormFieldInput,
} from "@/lib/form-field-input";
import { phrasesFor } from "@/i18n/phrases";

const phrases = phrasesFor("zh");

describe("form-field-input — 表单字段控件辅助", () => {
  it("maps kind to control — 种类映射控件", () => {
    expect(fieldControlKind({ name: "a", kind: "boolean", required: false })).toBe("checkbox");
    expect(fieldControlKind({ name: "a", kind: "date", required: false })).toBe("date");
    expect(
      fieldControlKind({ name: "a", kind: "enum", required: false, enumValues: ["open", "closed"] }),
    ).toBe("select");
    expect(fieldControlKind({ name: "a", kind: "enum", required: false, enumValues: [] })).toBe("text");
    expect(fieldControlKind({ name: "a", kind: "enum", required: false })).toBe("text");
    expect(fieldControlKind({ name: "a", kind: "integer", required: false })).toBe("number");
    expect(fieldControlKind({ name: "a", kind: "text", required: false })).toBe("text");
    expect(fieldControlKind({ name: "a", kind: "subjectRef", required: false })).toBe("text");
    expect(fieldControlKind({ name: "a", kind: "organizationRef", required: false })).toBe("text");
    expect(fieldControlKind({ name: "a", kind: "userRef", required: false })).toBe("text");
    expect(fieldControlKind({ name: "a", kind: "orgRef", required: false })).toBe("text");
  });

  it("labels kinds in zh — 中文种类文案", () => {
    expect(fieldKindLabel("text", phrases)).toBe("文本");
    expect(fieldKindLabel("integer", phrases)).toBe("整数");
    expect(fieldKindLabel("boolean", phrases)).toBe("布尔");
    expect(fieldKindLabel("date", phrases)).toBe("日期");
    expect(fieldKindLabel("enum", phrases)).toBe("枚举");
    expect(fieldKindLabel("subjectRef", phrases)).toBe("选主体");
    expect(fieldKindLabel("organizationRef", phrases)).toBe("选组织");
    expect(fieldKindLabel("userRef", phrases)).toBe("选主体");
    expect(fieldKindLabel("orgRef", phrases)).toBe("选组织");
  });

  it("defaults boolean empty to false — 布尔初始为 false", () => {
    expect(emptyFieldValue({ name: "ok", kind: "boolean", required: true })).toBe("false");
    expect(emptyFieldValue({ name: "t", kind: "text", required: false })).toBe("");
    expect(emptyFieldValues([{ name: "ok", kind: "boolean", required: true }, { name: "t", kind: "text", required: false }])).toEqual({
      ok: "false",
      t: "",
    });
  });

  it("coerces payload values — 强制转换提交值", () => {
    const integer: FormFieldInput = { name: "n", kind: "integer", required: true };
    const boolean: FormFieldInput = { name: "ok", kind: "boolean", required: true };
    const date: FormFieldInput = { name: "d", kind: "date", required: false };
    const en: FormFieldInput = { name: "s", kind: "enum", required: false, enumValues: ["a", "b"] };
    const text: FormFieldInput = { name: "t", kind: "text", required: false };

    expect(coerceFormFieldValue(integer, "42")).toBe(42);
    expect(coerceFormFieldValue(boolean, "true")).toBe(true);
    expect(coerceFormFieldValue(boolean, "false")).toBe(false);
    expect(coerceFormFieldValue(boolean, "")).toBe(false);
    expect(coerceFormFieldValue(date, "2026-10-08")).toBe("2026-10-08");
    expect(coerceFormFieldValue(en, "a")).toBe("a");
    expect(coerceFormFieldValue(text, "hi")).toBe("hi");
    expect(coerceFormFieldValue(text, "  ")).toBeUndefined();
    expect(coerceFormFieldValue(date, "")).toBeUndefined();
  });

  it("builds payload skipping empty optional — 组装 payload 跳过可选空", () => {
    const fields: FormFieldInput[] = [
      { name: "title", kind: "text", required: true },
      { name: "count", kind: "integer", required: false },
      { name: "ok", kind: "boolean", required: true },
      { name: "when", kind: "date", required: false },
      { name: "status", kind: "enum", required: false, enumValues: ["open", "closed"] },
    ];
    expect(
      buildFormPayload(fields, {
        title: "hello",
        count: "",
        ok: "true",
        when: "",
        status: "open",
      }),
    ).toEqual({
      title: "hello",
      ok: true,
      status: "open",
    });
    expect(
      buildFormPayload(fields, {
        title: "hello",
        count: "3",
        ok: "false",
        when: "2026-01-02",
        status: "",
      }),
    ).toEqual({
      title: "hello",
      count: 3,
      ok: false,
      when: "2026-01-02",
    });
  });

  it("detects missing required — 检测必填缺失", () => {
    expect(isRequiredFieldMissing({ name: "t", kind: "text", required: true }, "")).toBe(true);
    expect(isRequiredFieldMissing({ name: "t", kind: "text", required: true }, "x")).toBe(false);
    expect(isRequiredFieldMissing({ name: "t", kind: "text", required: false }, "")).toBe(false);
    expect(isRequiredFieldMissing({ name: "ok", kind: "boolean", required: true }, "false")).toBe(false);
    expect(isRequiredFieldMissing({ name: "ok", kind: "boolean", required: true }, "")).toBe(true);
  });

  it("picks subject/organization pickers by kind or name — 按 kind 或字段名选主体/组织", () => {
    expect(fieldPickerRole({ name: "assignee", kind: "text", required: false })).toBe("subject");
    expect(fieldPickerRole({ name: "owner", kind: "subjectRef", required: false })).toBe("subject");
    expect(fieldPickerRole({ name: "owner", kind: "userRef", required: false })).toBe("subject");
    expect(fieldPickerRole({ name: "orgUnit", kind: "text", required: false })).toBe("organization");
    expect(fieldPickerRole({ name: "dept", kind: "organizationRef", required: false })).toBe("organization");
    expect(fieldPickerRole({ name: "dept", kind: "orgRef", required: false })).toBe("organization");
    expect(fieldPickerRole({ name: "title", kind: "text", required: false })).toBeNull();
  });
});
