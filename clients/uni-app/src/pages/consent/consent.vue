<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的同意</text>
      <text class="muted">登录后可以查看、同意或撤回您对某项说明的决定。</text>
      <navigator url="/pages/login/login" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的同意</text>
      <text class="muted">
        这里记录您对某项说明作出的同意。同意或撤回都由您本人决定，其他人不能代您操作。
        请确认用途编号和文本版本与您正在阅读的说明完全一致。本页不会显示说明正文。
      </text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <button size="mini" :disabled="busy || loading" @click="refresh">刷新</button>
    </view>

    <view class="card" v-if="signedIn && offerPurpose">
      <text class="title">请确认这项说明</text>
      <text class="muted">服务请您确认下面这项说明。用途编号和文本版本必须与您看到的内容完全一致，才能记录同意。</text>
      <text class="label">用途编号</text>
      <text class="value mono">{{ offerPurpose }}</text>
      <text class="label">文本版本</text>
      <text class="value mono">{{ offerVersion }}</text>
      <text class="ok" v-if="alreadyGranted">您已经同意了这个版本。</text>
      <button
        v-else
        size="mini"
        type="primary"
        :disabled="busy || !offerVersion"
        @click="onGrant(offerPurpose, offerVersion)"
      >
        {{ busy ? "请稍候…" : "确认同意" }}
      </button>
    </view>

    <view class="card" v-if="signedIn && !loading">
      <text class="title">当前有效的同意</text>
      <text class="muted" v-if="!currentItems.length">目前没有任何有效的同意。</text>
      <view v-for="item in currentItems" :key="item.eventId || item.purposeKey" class="item">
        <text class="entry-title">用途 {{ item.purposeKey }}</text>
        <text class="muted">文本版本 {{ item.consentVersionId }}</text>
        <text class="muted" v-if="item.grantedAt">同意时间 {{ formatTime(item.grantedAt) }}</text>
        <button size="mini" :disabled="busy" @click="onWithdraw(item.purposeKey || '')">
          撤回这项同意
        </button>
      </view>
    </view>

    <view class="card" v-if="signedIn">
      <text class="title">记录一项新的同意</text>
      <text class="muted">如果您刚刚阅读了一份说明，请把说明上的用途编号和文本版本填在下面，核对无误后再同意。</text>
      <text class="label">用途编号</text>
      <input class="input" v-model="purposeInput" maxlength="64" placeholder="请填写说明上的用途编号" />
      <text class="label">文本版本</text>
      <input class="input" v-model="versionInput" maxlength="64" placeholder="请填写说明上的文本版本" />
      <button
        size="mini"
        type="primary"
        :disabled="busy || !purposeInput.trim() || !versionInput.trim()"
        @click="onGrant(purposeInput, versionInput)"
      >
        {{ busy ? "请稍候…" : "确认同意" }}
      </button>
      <text class="ok" v-if="grantedNotice">{{ grantedNotice }}</text>
    </view>

    <view class="card" v-if="signedIn && !loading && withdrawnItems.length">
      <text class="title">已撤回、可再次同意</text>
      <text class="muted">下面这些用途您曾经撤回过。如需再次同意，请先确认文本版本是否仍是您正在阅读的那一版。</text>
      <view v-for="item in withdrawnItems" :key="item.purposeKey" class="item">
        <text class="entry-title">用途 {{ item.purposeKey }}</text>
        <text class="muted">上次的文本版本 {{ item.consentVersionId }}</text>
        <button size="mini" :disabled="busy" @click="fillGrant(item.purposeKey || '', item.consentVersionId || '')">
          填入并核对该版本
        </button>
      </view>
    </view>

    <view class="card" v-if="signedIn && !loading && historyItems.length">
      <text class="title">以往记录</text>
      <view v-for="item in historyItems" :key="item.eventId" class="item">
        <text class="entry-title">{{ eventKindLabel(item.eventKind) }} · 用途 {{ item.purposeKey }}</text>
        <text class="muted">文本版本 {{ item.consentVersionId }}{{ item.occurredAt ? " · " + formatTime(item.occurredAt) : "" }}</text>
      </view>
    </view>

    <navigator url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * End-user consent page: list current grants, grant, and withdraw per purpose.
 * 终端同意页：列出当前授予，同意，并按目的撤回。不提供操作员审批控件。
 */
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { isSignedIn } from "../../api/auth";
import {
  grantConsent,
  listConsentHistory,
  listCurrentConsents,
  withdrawConsent,
  type ConsentHistoryEvent,
  type CurrentConsent,
} from "../../api/consents";
import { PlatformHttpError, messageForHttpError, toastHttpError } from "../../api/platform";

const signedIn = ref(isSignedIn());
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const grantedNotice = ref("");
const currentItems = ref<CurrentConsent[]>([]);
const historyItems = ref<ConsentHistoryEvent[]>([]);
const purposeInput = ref("");
const versionInput = ref("");
const offerPurpose = ref("");
const offerVersion = ref("");

const alreadyGranted = computed(() => {
  const purpose = offerPurpose.value;
  const version = offerVersion.value;
  if (!purpose || !version) return false;
  return currentItems.value.some(
    (row) => row.purposeKey === purpose && row.consentVersionId === version
  );
});

/**
 * Withdrawn purposes that are not currently granted, newest event first.
 * 已撤回且当前无效的用途，按最近一次事件排列。
 */
const withdrawnItems = computed(() => {
  const current = new Set(currentItems.value.map((row) => row.purposeKey || "").filter(Boolean));
  const latest = new Map<string, ConsentHistoryEvent>();
  for (const event of historyItems.value) {
    const purpose = event.purposeKey || "";
    if (!purpose || current.has(purpose)) continue;
    if ((event.eventKind || "").toUpperCase() !== "WITHDRAW") continue;
    if (!latest.has(purpose)) latest.set(purpose, event);
  }
  return [...latest.values()];
});

function firstQuery(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (Array.isArray(value) && typeof value[0] === "string") return value[0].trim();
  return "";
}

function formatTime(raw: string): string {
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return raw;
  const p = (n: number) => (n < 10 ? "0" + n : String(n));
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function eventKindLabel(kind?: string): string {
  return (kind || "").toUpperCase() === "WITHDRAW" ? "已撤回" : "已同意";
}

function consentMessage(err: unknown): string {
  if (err instanceof PlatformHttpError && (err.status === 400 || err.status === 422)) {
    return "请检查用途编号和文本版本是否与说明上的内容完全一致";
  }
  return messageForHttpError(err);
}

async function refresh() {
  if (!signedIn.value) return;
  loading.value = true;
  error.value = "";
  try {
    const [current, history] = await Promise.all([listCurrentConsents(), listConsentHistory()]);
    currentItems.value = current;
    // Newest first for people reading the history — 历史按时间从新到旧，方便阅读.
    historyItems.value = [...history].reverse();
  } catch (e) {
    currentItems.value = [];
    historyItems.value = [];
    error.value = consentMessage(e);
    toastHttpError(e);
  } finally {
    loading.value = false;
  }
}

function fillGrant(purposeKey: string, consentVersionId: string) {
  purposeInput.value = purposeKey;
  versionInput.value = consentVersionId;
  grantedNotice.value = "";
  uni.showToast({ title: "请再核对该文本版本后确认同意", icon: "none" });
}

async function onGrant(purposeKey: string, consentVersionId: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  grantedNotice.value = "";
  try {
    const saved = await grantConsent(purposeKey, consentVersionId);
    grantedNotice.value = `已记录您对「${saved.purposeKey}」（版本 ${saved.consentVersionId}）的同意`;
    uni.showToast({ title: "已记录您的同意", icon: "none" });
    purposeInput.value = "";
    versionInput.value = "";
    await refresh();
  } catch (e) {
    error.value =
      e instanceof Error && e.message && !e.message.startsWith("platform ")
        ? e.message
        : consentMessage(e);
    uni.showToast({ title: error.value, icon: "none", duration: 2500 });
  } finally {
    busy.value = false;
  }
}

function onWithdraw(purposeKey: string) {
  if (!purposeKey || busy.value) return;
  uni.showModal({
    title: "撤回同意",
    content: `确定要撤回对「${purposeKey}」的同意吗？撤回后，在您再次同意之前将不再有效。`,
    confirmText: "撤回",
    cancelText: "取消",
    success: (res) => {
      if (res.confirm) void doWithdraw(purposeKey);
    },
  });
}

async function doWithdraw(purposeKey: string) {
  busy.value = true;
  error.value = "";
  try {
    await withdrawConsent(purposeKey);
    uni.showToast({ title: "已撤回这项同意", icon: "none" });
    await refresh();
  } catch (e) {
    error.value = consentMessage(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

onLoad((query) => {
  const purpose = firstQuery(query?.purposeKey) || firstQuery(query?.purpose);
  const version = firstQuery(query?.consentVersionId) || firstQuery(query?.version);
  if (purpose) {
    offerPurpose.value = purpose;
    offerVersion.value = version;
    purposeInput.value = purpose;
    versionInput.value = version;
  }
});

onShow(() => {
  signedIn.value = isSignedIn();
  void refresh();
});
</script>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 20rpx; }
.card {
  background: #fff; border-radius: 12rpx; padding: 24rpx;
  display: flex; flex-direction: column; gap: 12rpx; border: 1px solid #e5e5e5;
}
.title { font-size: 32rpx; font-weight: 600; }
.entry-title { font-size: 28rpx; font-weight: 600; }
.label { color: #888; font-size: 22rpx; }
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.ok { color: #15803d; font-size: 24rpx; }
.value { font-size: 28rpx; word-break: break-all; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 24rpx; }
.input { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.item { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 0; border-top: 1px solid #eee; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
