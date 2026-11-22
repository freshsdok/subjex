import { describe, expect, it } from "vitest";
import { phrasesFor } from "@/i18n/phrases";
import {
  apiNotificationSendChannelFilter,
  apiNotificationSendRecipientFilter,
  apiNotificationSendStatusFilter,
  apiNotificationSendTimeFilter,
  notificationSendChannelPhraseKey,
  notificationSendDateTimeLocalValue,
  notificationSendListQueryString,
  notificationSendQueryErrorPhraseKey,
  notificationSendRecipientLine,
  notificationSendStatusPhraseKey,
} from "@/lib/notification-sends-console";

describe("notification-sends-console", () => {
  it("maps statuses, channels, and error reasons to phrase keys — 状态、渠道与错误原因映射到文案键", () => {
    expect(notificationSendStatusPhraseKey("pending")).toBe("notificationSendsStatusPending");
    expect(notificationSendStatusPhraseKey("PUBLISHED")).toBe("notificationSendsStatusPublished");
    expect(notificationSendStatusPhraseKey("dead")).toBe("notificationSendsStatusDead");
    expect(notificationSendStatusPhraseKey("unknown")).toBeUndefined();
    expect(notificationSendChannelPhraseKey("sms")).toBe("notificationSendsChannelSms");
    expect(notificationSendChannelPhraseKey("WECHAT_TEMPLATE")).toBe("notificationSendsChannelWechat");
    expect(notificationSendChannelPhraseKey("fax")).toBeUndefined();
    expect(notificationSendQueryErrorPhraseKey("notification-send-not-found")).toBe("notificationSendsNotFound");
    expect(notificationSendQueryErrorPhraseKey("notification-cross-tenant-denied")).toBe(
      "notificationSendsCrossTenantForbidden",
    );
    expect(notificationSendQueryErrorPhraseKey("other")).toBeUndefined();
  });

  it("sends only known literal filters to the list API — 仅把已知且字面的筛选传给列表接口", () => {
    expect(apiNotificationSendStatusFilter("all")).toBe("");
    expect(apiNotificationSendStatusFilter("DEAD")).toBe("DEAD");
    expect(apiNotificationSendStatusFilter("nope")).toBe("");
    expect(apiNotificationSendChannelFilter("email")).toBe("EMAIL");
    expect(apiNotificationSendChannelFilter("FAX")).toBe("");
    expect(apiNotificationSendRecipientFilter("ada@example.test")).toBe("ada@example.test");
    expect(apiNotificationSendRecipientFilter("ada%")).toBe("");
    expect(apiNotificationSendRecipientFilter('ada"x')).toBe("");
    expect(apiNotificationSendRecipientFilter("")).toBe("");
  });

  it("converts local datetime and ISO instants for the time window — 把本地日期时间与 ISO 时间转为时间窗", () => {
    expect(apiNotificationSendTimeFilter("")).toBe("");
    expect(apiNotificationSendTimeFilter("not-a-date")).toBe("");
    expect(apiNotificationSendTimeFilter("2026-11-22T08:00:00Z")).toBe("2026-11-22T08:00:00.000Z");
    const fromLocal = apiNotificationSendTimeFilter("2026-11-22T16:00");
    expect(fromLocal).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect(new Date(fromLocal).getTime()).toBe(new Date("2026-11-22T16:00:00").getTime());
    expect(notificationSendDateTimeLocalValue("2026-11-22T16:00")).toBe("2026-11-22T16:00");
    expect(notificationSendDateTimeLocalValue("2026-11-22T08:00:00.000Z")).toBe(
      notificationSendDateTimeLocalValue(new Date("2026-11-22T08:00:00.000Z").toISOString()),
    );
  });

  it("formats the recipient line and keeps list filters in the query — 格式化收件人并在查询串中保留筛选", () => {
    expect(
      notificationSendRecipientLine(phrasesFor("zh"), {
        recipientRealm: "END_USER",
        recipientSubjectId: "end-user-ada",
        recipientDestination: "ada@example.test",
      }),
    ).toBe("用户 end-user-ada ada@example.test");
    expect(
      notificationSendRecipientLine(phrasesFor("zh"), {
        recipientDestination: "+8613800000000",
      }),
    ).toBe("+8613800000000");
    expect(
      notificationSendListQueryString({
        tenantId: "tenant-a",
        status: "DEAD",
        channel: "SMS",
        recipient: "ada@example.test",
        from: "2026-11-22T16:00",
      }),
    ).toBe(
      "tenantId=tenant-a&status=DEAD&channel=SMS&recipient=ada%40example.test&from=2026-11-22T16%3A00",
    );
    expect(notificationSendListQueryString({})).toBe("");
  });
});
