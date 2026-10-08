import { describe, expect, it } from "vitest";
import {
  buildListFilterQueryString,
  isGenericEntityRecordsPath,
  listFilterFieldOptions,
  listFilterHasActive,
  parseListFilterSearchParams,
  platformPathWithListFilter,
} from "@/lib/list-filter-query";

describe("list-filter-query — 列表筛选查询辅助", () => {
  it("detects generic entity records paths — 识别通用实体 records 路径", () => {
    expect(isGenericEntityRecordsPath("/api/v1/entities/demo-ticket/records")).toBe(true);
    expect(isGenericEntityRecordsPath("entities/demo-ticket/records")).toBe(true);
    expect(isGenericEntityRecordsPath("/api/v1/entities/service-note/notes")).toBe(false);
    expect(isGenericEntityRecordsPath("/api/v1/forms/endpoint-publication/submissions")).toBe(false);
    expect(isGenericEntityRecordsPath("/api/v1/entities/demo-ticket/records/extra")).toBe(false);
  });

  it("builds query only for complete params — 仅完整参数拼 query", () => {
    expect(buildListFilterQueryString({})).toBe("");
    expect(buildListFilterQueryString({ sort: "title", order: "desc" })).toBe("?sort=title&order=desc");
    expect(
      buildListFilterQueryString({
        sort: "status",
        order: "asc",
        filterField: "status",
        filterValue: "open",
      }),
    ).toBe("?sort=status&order=asc&filterField=status&filterValue=open");
    // Incomplete filter (xor) is dropped.
    expect(buildListFilterQueryString({ filterField: "status" })).toBe("");
    expect(buildListFilterQueryString({ filterValue: "open" })).toBe("");
    expect(buildListFilterQueryString({ filterField: "status", filterValue: "  " })).toBe("");
    expect(buildListFilterQueryString({ order: "nope" as "asc" })).toBe("");
  });

  it("parses searchParams and encodes — 解析 searchParams 并编码", () => {
    expect(
      parseListFilterSearchParams({
        sort: " title ",
        order: "DESC",
        filterField: "status",
        filterValue: "a b",
      }),
    ).toEqual({ sort: "title", order: "desc", filterField: "status", filterValue: "a b" });
    expect(
      parseListFilterSearchParams({
        filterField: "status",
        filterValue: undefined,
      }),
    ).toEqual({});
    expect(buildListFilterQueryString({ filterField: "a/b", filterValue: "x y" })).toBe(
      "?filterField=a%2Fb&filterValue=x%20y",
    );
  });

  it("appends query to platform path — 拼到平台路径", () => {
    expect(platformPathWithListFilter("entities/demo-ticket/records", { sort: "title" })).toBe(
      "entities/demo-ticket/records?sort=title",
    );
    expect(
      platformPathWithListFilter("entities/demo-ticket/records?stale=1", {
        filterField: "status",
        filterValue: "open",
      }),
    ).toBe("entities/demo-ticket/records?filterField=status&filterValue=open");
  });

  it("resolves field options — 解析字段选项", () => {
    expect(listFilterFieldOptions(["a", "b"], [], {})).toEqual(["a", "b"]);
    expect(listFilterFieldOptions(undefined, [{ ticketId: "1", title: "t" }], {})).toEqual([
      "ticketId",
      "title",
    ]);
    expect(
      listFilterFieldOptions(undefined, [], { filterField: "status", sort: "title" }),
    ).toEqual(["status", "title"]);
    expect(listFilterFieldOptions(undefined, [], {})).toEqual([]);
  });

  it("detects active filter — 判断是否有生效筛选", () => {
    expect(listFilterHasActive({})).toBe(false);
    expect(listFilterHasActive({ sort: "title" })).toBe(true);
    expect(listFilterHasActive({ filterField: "a", filterValue: "b" })).toBe(true);
    expect(listFilterHasActive({ filterField: "a" })).toBe(false);
  });
});
