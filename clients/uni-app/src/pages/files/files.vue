<template>
  <view class="page">
    <view class="card">
      <text class="title">我的文件</text>
      <text class="muted">您可以上传图片、PDF 或常用办公文档，单个文件不超过 50MB。较大的文件会自动分段上传。</text>
      <view class="row" v-if="signedIn">
        <button size="mini" type="primary" :disabled="busy" @click="onUpload">
          {{ busy ? "请稍候…" : "上传文件" }}
        </button>
        <button size="mini" :disabled="busy" @click="refresh">刷新</button>
      </view>
      <navigator v-if="!signedIn" :url="loginHref" class="link">请先登录后再查看文件</navigator>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <text class="muted">只有你点分享或复制链接时，才会记下一次分享；带着「来自分享」或活动/来源参数打开某个文件时，才会记下一次打开。在「我的文件」列表里普通打开，不会被悄悄记录。</text>
    </view>

    <view class="card" v-if="signedIn && !loading && !items.length && !error">
      <text class="muted">还没有文件。点击「上传文件」添加第一份吧。</text>
    </view>

    <view v-for="item in items" :key="item.objectKey" class="card">
      <text class="entry-title">{{ displayName(item) }}</text>
      <text class="muted">{{ typeLabel(item.mediaType) }}{{ item.createdAt ? " · " + formatTime(item.createdAt) : "" }}</text>
      <view class="row">
        <button size="mini" :disabled="busy" @click="onOpen(item)">打开</button>
        <button
          v-if="fileResourceId(item)"
          size="mini"
          class="share-btn"
          :disabled="busy"
          @click="onCopyShareLink(item)"
        >复制分享链接</button>
        <button
          v-if="fileResourceId(item)"
          size="mini"
          class="share-btn"
          open-type="share"
          :disabled="busy"
          @click="prepareWeChatShare(item)"
        >分享</button>
      </view>
    </view>

  </view>
</template>

<script setup lang="ts">
/**
 * Files: list, upload (multipart or chunked), open via signed download — 文件：列表、上传（小文件一次/大文件分片）、经签名链接打开。
 * <p>
 * Share/open events follow the questionnaire rule: explicit share, or a shared/campaign/UTM entry.
 * Ordinary list opens are not posted. Inbox is not posted from this page.
 * 分享/打开遵循问卷规则：显式分享，或带着分享/活动/UTM 进入。列表里的普通打开不上报。本页不上报站内信。
 */
import { ref, onMounted } from "vue";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import { listObjects, openObject, pickFile, uploadObject, type EndUserObject } from "../../api/objects";
import { messageForHttpError, toastHttpError } from "../../api/platform";
import { applyTenantFromQuery, readTenantId } from "../../api/tenant";
import {
  RESOURCE_TYPE_FILE,
  attributionFromQuery,
  buildShareQueryString,
  fileResourceId,
  fileResourceIdFromQuery,
  isSharedOpenQuery,
  tryRecordResourceEvent,
  type ResourceEventAttribution,
} from "../../api/resource-events";

const signedIn = ref(isSignedIn());
const loginHref = loginPageUrl("/pages/files/files");
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const items = ref<EndUserObject[]>([]);
const attribution = ref<ResourceEventAttribution>({});
const shareTargetId = ref("");

function displayName(item: EndUserObject): string {
  const key = item.objectKey || "未命名文件";
  const parts = key.split("-");
  return parts.length > 2 ? "文件 " + parts[parts.length - 1].slice(0, 8) : key;
}

function typeLabel(mediaType?: string | null): string {
  const t = (mediaType || "").toLowerCase();
  if (t.includes("pdf")) return "PDF";
  if (t.includes("jpeg") || t.includes("jpg") || t.includes("png") || t.includes("webp")) return "图片";
  if (t.includes("word")) return "文档";
  if (t.includes("sheet") || t.includes("excel")) return "表格";
  if (t.includes("presentation") || t.includes("powerpoint")) return "演示文稿";
  if (t.includes("text")) return "文本";
  return "文件";
}

function formatTime(raw: string): string {
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return raw;
  const p = (n: number) => (n < 10 ? "0" + n : String(n));
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function fileShareQuery(resourceId: string): string {
  return buildShareQueryString({ objectId: resourceId }, attribution.value, readTenantId() || undefined);
}

function fileSharePath(resourceId: string): string {
  return `/pages/files/files?${fileShareQuery(resourceId)}`;
}

function shareHref(resourceId: string): string {
  const path = fileSharePath(resourceId);
  try {
    if (typeof window !== "undefined" && window.location && window.location.href) {
      const url = new URL(window.location.href);
      if (url.hash) {
        url.hash = `#${path}`;
        return url.toString();
      }
    }
  } catch {
    // Mini-program or restricted runtime — 小程序或受限运行时。
  }
  return path;
}

function record(eventType: "share" | "open", resourceId: string) {
  const id = (resourceId || "").trim();
  if (!id) return;
  tryRecordResourceEvent({
    resourceType: RESOURCE_TYPE_FILE,
    resourceId: id,
    eventType,
    tenantId: readTenantId() || undefined,
    ...attribution.value,
  });
}

function onCopyShareLink(item: EndUserObject) {
  const resourceId = fileResourceId(item);
  if (!resourceId) return;
  shareTargetId.value = resourceId;
  const href = shareHref(resourceId);
  uni.setClipboardData({
    data: href,
    success: () => {
      record("share", resourceId);
      uni.showToast({ title: "链接已复制", icon: "none" });
    },
  });
}

function prepareWeChatShare(item: EndUserObject) {
  shareTargetId.value = fileResourceId(item);
}

onShareAppMessage(() => {
  const resourceId = shareTargetId.value.trim();
  if (!resourceId) {
    return {
      title: "我的文件",
      path: "/pages/files/files",
    };
  }
  record("share", resourceId);
  return {
    title: "分享文件",
    path: fileSharePath(resourceId),
  };
});

async function refresh() {
  if (!signedIn.value) return;
  loading.value = true;
  error.value = "";
  try {
    items.value = await listObjects();
  } catch (e) {
    items.value = [];
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    loading.value = false;
  }
}

async function onUpload() {
  busy.value = true;
  error.value = "";
  try {
    const file = await pickFile();
    await uploadObject(file);
    uni.showToast({ title: "上传成功", icon: "none" });
    await refresh();
  } catch (e) {
    const cancelled = e instanceof Error && /cancel|取消/.test(e.message);
    if (!cancelled) {
      error.value = messageForHttpError(e);
      toastHttpError(e);
    }
  } finally {
    busy.value = false;
  }
}

/**
 * Ordinary list open: never post an open event.
 * 列表里的普通打开：不上报打开事件。
 * A shared/campaign/UTM entry is recorded once in onLoad, not again here.
 * 分享/活动/UTM 入口已在 onLoad 记过一次，这里不再重复。
 */
async function onOpen(item: EndUserObject) {
  busy.value = true;
  error.value = "";
  try {
    await openObject(item.objectKey);
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

onLoad((query) => {
  applyTenantFromQuery(query ?? {});
  attribution.value = attributionFromQuery(query ?? {});
  const resourceId = fileResourceIdFromQuery(query ?? {});
  if (resourceId) {
    shareTargetId.value = resourceId;
  }
  // Record an open only for a shared/campaign/UTM entry, matching the questionnaire rule.
  // 仅在分享/活动/UTM 入口记一次打开，与问卷规则相同。
  if (resourceId && isSharedOpenQuery(query ?? {})) {
    record("open", resourceId);
  }
});

onMounted(() => {
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
.row { display: flex; align-items: center; gap: 16rpx; flex-wrap: wrap; }
.link { color: #2563eb; font-size: 28rpx; }
.share-btn { margin-top: 0; }
</style>
