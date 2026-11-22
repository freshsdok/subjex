<template>
  <view class="page">
    <view class="card">
      <text class="title">我的文件</text>
      <text class="muted">您可以上传图片、PDF 或常用办公文档，单个文件不超过 50MB。</text>
      <view class="row" v-if="signedIn">
        <button size="mini" type="primary" :disabled="busy" @click="onUpload">
          {{ busy ? "请稍候…" : "上传文件" }}
        </button>
        <button size="mini" :disabled="busy" @click="refresh">刷新</button>
      </view>
      <navigator v-if="!signedIn" :url="loginHref" class="link">请先登录后再查看文件</navigator>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
    </view>

    <view class="card" v-if="signedIn && !loading && !items.length && !error">
      <text class="muted">还没有文件。点击「上传文件」添加第一份吧。</text>
    </view>

    <view v-for="item in items" :key="item.objectKey" class="card">
      <text class="entry-title">{{ displayName(item) }}</text>
      <text class="muted">{{ typeLabel(item.mediaType) }}{{ item.createdAt ? " · " + formatTime(item.createdAt) : "" }}</text>
      <button size="mini" :disabled="busy" @click="onOpen(item.objectKey)">打开</button>
    </view>

    <navigator url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * Files: list, upload, open via signed download — 文件：列表、上传、经签名链接打开。
 */
import { ref, onMounted } from "vue";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import { listObjects, openObject, pickFile, uploadObject, type EndUserObject } from "../../api/objects";
import { messageForHttpError, toastHttpError } from "../../api/platform";

const signedIn = ref(isSignedIn());
const loginHref = loginPageUrl("/pages/files/files");
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const items = ref<EndUserObject[]>([]);

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

async function onOpen(objectKey: string) {
  busy.value = true;
  error.value = "";
  try {
    await openObject(objectKey);
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

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
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
