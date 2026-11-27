import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  attributionFromQuery,
  buildShareQueryString,
  fileResourceId,
  fileResourceIdFromQuery,
  isSharedOpenQuery,
  resolvedReferrer,
  RESOURCE_TYPE_FILE,
} from "./resource-event-query.ts";

describe("shared-open query rule (questionnaire and files)", () => {
  it("does not treat an ordinary visit as an open", () => {
    assert.equal(isSharedOpenQuery({}), false);
    assert.equal(isSharedOpenQuery({ objectId: "11111111-1111-1111-1111-111111111111" }), false);
    assert.equal(isSharedOpenQuery({ id: "q-1" }), false);
    assert.equal(isSharedOpenQuery({ shared: "0" }), false);
  });

  it("treats shared=1 / campaign / UTM as a shared open, same as the questionnaire rule", () => {
    assert.equal(isSharedOpenQuery({ shared: "1" }), true);
    assert.equal(isSharedOpenQuery({ shared: "true" }), true);
    assert.equal(isSharedOpenQuery({ shared: "yes" }), true);
    assert.equal(isSharedOpenQuery({ campaign: "spring-hello" }), true);
    assert.equal(isSharedOpenQuery({ utm_source: "wechat" }), true);
    assert.equal(isSharedOpenQuery({ utmSource: "wechat" }), true);
    assert.equal(RESOURCE_TYPE_FILE, "file");
  });
});

describe("file resource id and share query", () => {
  it("prefers objectId and keeps attribution on the share link without copying a document referrer", () => {
    assert.equal(
      fileResourceId({ objectId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", objectKey: "eu-sub-1" }),
      "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    );
    assert.equal(fileResourceId({ objectKey: "eu-user-1-aaaa" }), "eu-user-1-aaaa");
    assert.equal(fileResourceId({ objectKey: "path/not-a-token" }), "");
    assert.equal(
      fileResourceIdFromQuery({ objectId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", shared: "1" }),
      "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    );
    const query = buildShareQueryString(
      { objectId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" },
      { campaign: "spring", utmSource: "wechat" },
      "tenant-a",
    );
    const params = new URLSearchParams(query);
    assert.equal(params.get("shared"), "1");
    assert.equal(params.get("objectId"), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    assert.equal(params.get("campaign"), "spring");
    assert.equal(params.get("utm_source"), "wechat");
    assert.equal(params.get("tenantId"), "tenant-a");
    assert.equal(params.get("referrer"), null);
    assert.equal(isSharedOpenQuery(Object.fromEntries(params.entries())), true);
  });
});

describe("captured referrer", () => {
  it("prefers a query/attribution referrer over a document referrer", () => {
    assert.equal(attributionFromQuery({ referrer: "https://example.com/a" }).referrer, "https://example.com/a");
    assert.equal(resolvedReferrer({ referrer: "https://app/x" }, "https://doc"), "https://app/x");
    assert.equal(resolvedReferrer({}, "https://doc"), "https://doc");
    assert.equal(resolvedReferrer({}, "  "), undefined);
  });
});
