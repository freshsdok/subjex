import { describe, expect, it } from "vitest";
import { buildDefaultFlowSorterOptions } from "@/lib/flow-sorter";

describe("flow-sorter helpers — 流程分拣器辅助", () => {
  it("builds list and new when paths present — 有路径时生成列表与新建", () => {
    expect(
      buildDefaultFlowSorterOptions(
        { listPath: "/pages/demo-ticket", submitPath: "/pages/demo-ticket/new" },
        { list: "列表", newItem: "新建" },
      ),
    ).toEqual([
      { key: "list", label: "列表", href: "/pages/demo-ticket" },
      { key: "new", label: "新建", href: "/pages/demo-ticket/new" },
    ]);
  });

  it("omits missing paths and templated detail — 缺路径与模板详情省略", () => {
    expect(
      buildDefaultFlowSorterOptions(
        { listPath: "  ", submitPath: null, detailPath: "/pages/demo-ticket/{id}" },
        { list: "List", newItem: "New", detail: "Detail" },
      ),
    ).toEqual([]);
    expect(
      buildDefaultFlowSorterOptions(
        {
          listPath: "/pages/x",
          submitPath: "/pages/x/new",
          detailPath: "/pages/x/abc",
        },
        { list: "List", newItem: "New", detail: "Detail" },
      ),
    ).toEqual([
      { key: "list", label: "List", href: "/pages/x" },
      { key: "new", label: "New", href: "/pages/x/new" },
      { key: "detail", label: "Detail", href: "/pages/x/abc" },
    ]);
  });
});
