<template>
  <view class="page">
    <view class="card">
      <text class="title">{{ page?.title || "内容页" }}</text>
      <text class="muted">
        这里展示本组织已发布的内容页。无需登录即可阅读。草稿不会出现在这里。
      </text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <button size="mini" :disabled="loading" @click="refresh">刷新</button>
    </view>

    <view class="card" v-if="!slug && !loading && !error">
      <text class="title">已发布的页面</text>
      <text class="muted" v-if="!summaries.length">暂时没有已发布的内容页。</text>
      <navigator
        v-for="item in summaries"
        :key="item.slug"
        :url="'/pages/content/content?slug=' + encodeURIComponent(item.slug || '')"
        class="item link"
      >
        <text class="entry-title">{{ item.title }}</text>
        <text class="muted">{{ item.slug }}</text>
      </navigator>
    </view>

    <view class="card" v-if="slug && page && !loading">
      <view v-for="block in page.blocks || []" :key="block.blockId || block.type" class="block">
        <text v-if="block.type === 'TITLE'" class="entry-title">{{ block.text }}</text>
        <text v-else-if="block.type === 'TEXT'" class="body">{{ block.text }}</text>
        <image
          v-else-if="block.type === 'IMAGE' && mediaSrc(block)"
          :src="mediaSrc(block)"
          :alt="block.alt || ''"
          mode="widthFix"
          class="image"
        />
        <text v-else-if="block.type === 'IMAGE'" class="muted">{{ block.alt || "图片" }}</text>
        <navigator
          v-else-if="block.type === 'LINK' && isAppPath(block.href)"
          :url="block.href || ''"
          class="link"
        >
          {{ block.text }}
        </navigator>
        <text v-else-if="block.type === 'LINK'" class="link" @click="openExternal(block.href)">{{ block.text }}</text>
      </view>
    </view>

    <navigator url="/pages/index/index" open-type="switchTab" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * Public published content pages in the current tenant. Readable without login.
 * 当前租户已发布的内容页。无需登录即可阅读。
 */
import { ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import {
  getPublishedContentPage,
  listPublishedContentPages,
  publishedMediaSrc,
  type PublicContentBlock,
  type PublicContentPage,
  type PublicContentSummary,
} from "../../api/content";
import { PlatformHttpError } from "../../api/platform";

const slug = ref("");
const loading = ref(false);
const error = ref("");
const summaries = ref<PublicContentSummary[]>([]);
const page = ref<PublicContentPage | null>(null);

function mediaSrc(block: PublicContentBlock): string {
  return publishedMediaSrc(block);
}

function isAppPath(href?: string | null): boolean {
  return !!href && href.startsWith("/pages/");
}

function openExternal(href?: string | null) {
  if (!href) return;
  // #ifdef H5
  window.open(href, "_blank");
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: href });
  uni.showToast({ title: "链接已复制", icon: "none" });
  // #endif
}

function contentMessage(err: unknown): string {
  if (err instanceof PlatformHttpError) {
    if (err.status === 404) return "没有找到这份已发布的内容页。";
    return err.message || "未能加载内容页。";
  }
  return "未能加载内容页。";
}

async function refresh() {
  loading.value = true;
  error.value = "";
  try {
    if (slug.value) {
      page.value = await getPublishedContentPage(slug.value);
      summaries.value = [];
    } else {
      page.value = null;
      summaries.value = await listPublishedContentPages();
    }
  } catch (err) {
    error.value = contentMessage(err);
    page.value = null;
    summaries.value = [];
  } finally {
    loading.value = false;
  }
}

onLoad((query) => {
  slug.value = ((query && query.slug) || "").toString().trim();
});

onShow(() => {
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
.body { font-size: 26rpx; color: #222; white-space: pre-wrap; }
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.item { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 0; border-top: 1px solid #eee; }
.block { display: flex; flex-direction: column; gap: 8rpx; padding: 8rpx 0; }
.image { width: 100%; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
