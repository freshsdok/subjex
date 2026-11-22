<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的导出</text>
      <text class="muted">登录后可以申请导出您在本组织的个人资料，并查看申请进度。</text>
      <navigator url="/pages/login/login" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的导出</text>
      <text class="muted">
        您可以申请一份个人资料副本。申请需要工作人员审核，通过后会为您准备文件。
        文件准备好后请尽快下载，过期后需要重新申请。本页不会出现批准或驳回按钮。
      </text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <button size="mini" :disabled="busy || loading" @click="refresh">刷新</button>
    </view>

    <view class="card" v-if="signedIn">
      <text class="title">提交一份申请</text>
      <text class="muted">将申请导出「我在本组织的全部个人资料」。提交后请等待审核，不能由您本人批准。</text>
      <text class="muted" v-if="hasInProgress">您已有正在处理的申请，可以等待结果，也可以再提交一份。</text>
      <button size="mini" type="primary" :disabled="busy" @click="onRequest">
        {{ busy ? "请稍候…" : "申请导出我的资料" }}
      </button>
      <text class="ok" v-if="submitNotice">{{ submitNotice }}</text>
    </view>

    <view class="card" v-if="signedIn && !loading">
      <text class="title">我的申请</text>
      <text class="muted" v-if="!items.length">还没有导出申请。需要时请点击上面的按钮提交。</text>
      <view v-for="item in items" :key="item.requestId" class="item">
        <text class="entry-title">{{ exportStatusLabel(displayStatus(item)) }}</text>
        <text class="muted">范围 {{ exportScopeLabel(item.scope) }}</text>
        <text class="muted" v-if="item.createdAt">申请时间 {{ formatTime(item.createdAt) }}</text>
        <text class="muted" v-if="item.decidedAt && displayStatus(item) !== 'REQUESTED'">
          处理时间 {{ formatTime(item.decidedAt) }}
        </text>
        <text class="muted" v-if="canDownloadExport(item)">
          请在 {{ formatTime(item.expiresAt || "") }} 前下载，过期后需重新申请。
        </text>
        <text class="muted" v-else-if="displayStatus(item) === 'EXPIRED' || (item.status === 'READY' && isExportExpired(item))">
          这份文件已过期，无法再下载。如需文件，请再提交一份申请。
        </text>
        <text class="muted" v-if="item.requestId">申请编号 {{ shortId(item.requestId) }}</text>
        <button
          v-if="canDownloadExport(item)"
          size="mini"
          type="primary"
          :disabled="busy"
          @click="onDownload(item)"
        >
          {{ busy ? "请稍候…" : "下载这份资料" }}
        </button>
      </view>
    </view>

    <navigator url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * End-user export requests: submit, track status, open a signed download when READY.
 * 终端导出：提交申请、查看进度；就绪后经已有短时签名链接下载。页面不提供批准或驳回。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { isSignedIn } from "../../api/auth";
import {
  canDownloadExport,
  createExportRequest,
  exportMessage,
  exportScopeLabel,
  exportStatusLabel,
  isExportExpired,
  isExportInProgress,
  listOwnExports,
  issueExportDownloadLink,
  openIssuedDownload,
  sortExportsNewestFirst,
  type EndUserExport,
} from "../../api/exports";
import { toastHttpError } from "../../api/platform";

const signedIn = ref(isSignedIn());
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const submitNotice = ref("");
const items = ref<EndUserExport[]>([]);

const hasInProgress = computed(() => items.value.some(isExportInProgress));

function formatTime(raw: string): string {
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return raw;
  const p = (n: number) => (n < 10 ? "0" + n : String(n));
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function shortId(id: string): string {
  const trimmed = id.trim();
  return trimmed.length > 12 ? trimmed.slice(0, 8) + "…" : trimmed;
}

/** Surface local expiry even if the list snapshot is still READY — 列表若仍是 READY 但已过期，按过期展示. */
function displayStatus(item: EndUserExport): string {
  const status = (item.status || "").toUpperCase();
  if (status === "READY" && isExportExpired(item)) return "EXPIRED";
  return status;
}

async function refresh() {
  if (!signedIn.value) return;
  loading.value = true;
  error.value = "";
  try {
    items.value = sortExportsNewestFirst(await listOwnExports());
  } catch (e) {
    items.value = [];
    error.value = exportMessage(e);
    toastHttpError(e);
  } finally {
    loading.value = false;
  }
}

function onRequest() {
  if (busy.value) return;
  uni.showModal({
    title: "申请导出",
    content: "确定要申请导出您在本组织的全部个人资料吗？申请需要工作人员审核，通过后才能下载，不能由您本人批准。",
    confirmText: "提交申请",
    cancelText: "取消",
    success: (res) => {
      if (res.confirm) void doRequest();
    },
  });
}

async function doRequest() {
  busy.value = true;
  error.value = "";
  submitNotice.value = "";
  try {
    await createExportRequest();
    submitNotice.value = "申请已提交，请等待工作人员审核。";
    uni.showToast({ title: "申请已提交", icon: "none" });
    await refresh();
  } catch (e) {
    error.value = exportMessage(e);
    uni.showToast({ title: error.value, icon: "none", duration: 2500 });
  } finally {
    busy.value = false;
  }
}

async function onDownload(item: EndUserExport) {
  if (!item.requestId || busy.value) return;
  if (!canDownloadExport(item)) {
    uni.showToast({ title: "这份导出还不能下载，或已经过期", icon: "none" });
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const link = await issueExportDownloadLink(item.requestId);
    if (!link.url) throw new Error("暂时无法打开这份导出");
    await openIssuedDownload(link.url);
    uni.showToast({ title: "请尽快打开，下载链接很快会失效", icon: "none", duration: 2500 });
  } catch (e) {
    error.value = exportMessage(e);
    uni.showToast({ title: error.value, icon: "none", duration: 2500 });
    await refresh();
  } finally {
    busy.value = false;
  }
}

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
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.ok { color: #15803d; font-size: 24rpx; }
.item { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 0; border-top: 1px solid #eee; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
