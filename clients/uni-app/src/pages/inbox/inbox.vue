<template>
  <view class="page">
    <view class="card">
      <text class="title">站内信 / Inbox (IM-6 END_USER)</text>
      <text class="muted">
        GET /api/v1/me/inbox/entries — realm END_USER. Tenant via header.
      </text>
      <input class="input" v-model="tenantId" placeholder="tenantId (e.g. platform)" />
      <view class="row">
        <button size="mini" type="primary" :disabled="!signedIn || busy" @click="refresh">
          刷新 / Refresh
        </button>
        <text class="badge">未读 / unread: {{ unread }}</text>
      </view>
      <text class="warn" v-if="!signedIn">请先登录 / Sign in first →</text>
      <navigator v-if="!signedIn" url="/pages/login/login" class="link">登录 / Sign in</navigator>
      <text class="muted" v-if="error">{{ error }}</text>
    </view>

    <view v-for="item in entries" :key="item.entryId" class="card">
      <text class="entry-title">{{ item.title || "(no title)" }}</text>
      <text class="body">{{ item.body }}</text>
      <text class="muted">{{ item.severity }} · {{ item.channel }} · {{ item.readAt ? "已读" : "未读" }}</text>
      <button
        v-if="!item.readAt"
        size="mini"
        :disabled="busy"
        @click="onMark(item.entryId)"
      >
        标为已读 / Mark read
      </button>
    </view>

    <view class="card" v-if="signedIn && !entries.length && !error">
      <text class="muted">暂无终端信件 / No END_USER messages yet.</text>
    </view>

    <navigator url="/pages/index/index" class="link bottom">← 首页 / Home</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * END_USER inbox list + mark-read (IM-6).
 * 终端收件列表与已读。
 */
import { ref, onMounted } from "vue";
import { isSignedIn } from "../../api/auth";
import { listMyInbox, markRead, unreadCount } from "../../api/inbox";
import type { InboxEntry } from "../../api/inbox";

const tenantId = ref("platform");
const entries = ref<InboxEntry[]>([]);
const unread = ref(0);
const error = ref("");
const busy = ref(false);
const signedIn = ref(isSignedIn());

async function refresh() {
  if (!signedIn.value) return;
  busy.value = true;
  error.value = "";
  try {
    const tid = tenantId.value.trim() || "platform";
    unread.value = await unreadCount(tid);
    entries.value = await listMyInbox({ tenantId: tid });
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    entries.value = [];
  } finally {
    busy.value = false;
  }
}

async function onMark(entryId: string) {
  busy.value = true;
  try {
    await markRead(tenantId.value.trim() || "platform", entryId);
    await refresh();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  signedIn.value = isSignedIn();
  if (signedIn.value) void refresh();
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
.body { font-size: 26rpx; color: #333; }
.muted { font-size: 22rpx; color: #888; }
.warn { color: #b45309; font-size: 24rpx; }
.input { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.row { display: flex; align-items: center; gap: 16rpx; }
.badge { font-size: 24rpx; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
