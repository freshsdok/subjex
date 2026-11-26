<template>
  <view class="page">
    <view class="card">
      <text class="title">站内信</text>
      <text class="muted">这里会收到来自平台的通知和提醒。信件里的附件可以直接打开。</text>
      <view class="row" v-if="signedIn">
        <button size="mini" type="primary" :disabled="busy" @click="refresh">
          {{ loading ? "正在刷新…" : "刷新" }}
        </button>
        <text class="badge" v-if="!loading">未读 {{ unread }} 条</text>
      </view>
      <navigator v-if="!signedIn" :url="loginHref" class="link">请先登录后再查看站内信</navigator>
      <text class="warn" v-if="error">{{ error }}</text>
    </view>

    <view class="card" v-if="signedIn && loading">
      <text class="muted">正在加载信件…</text>
    </view>

    <view class="card" v-if="signedIn && !loading && !entries.length && !error">
      <text class="muted">暂时还没有站内信。</text>
    </view>

    <view v-for="item in entries" :key="item.entryId" class="card">
      <text class="entry-title">{{ item.title || "未命名通知" }}</text>
      <text class="body">{{ item.body }}</text>
      <text class="muted">{{ item.readAt ? "已读" : "未读" }}{{ item.createdAt ? " · " + item.createdAt : "" }}</text>
      <view v-if="item.attachments && item.attachments.length" class="attachments">
        <text class="muted">附件</text>
        <view v-for="file in item.attachments" :key="file.attachmentId" class="row">
          <text class="file-name">{{ file.fileName }}</text>
          <button size="mini" :disabled="busy" @click="onOpen(item.entryId, file)">打开附件</button>
        </view>
      </view>
      <button
        v-if="!item.readAt"
        size="mini"
        :disabled="busy"
        @click="onMark(item.entryId)"
      >
        标为已读
      </button>
    </view>

  </view>
</template>

<script setup lang="ts">
/**
 * End-user inbox list, mark-read, and own-entry attachments — 终端站内信列表、已读与本人附件。
 */
import { ref, onMounted } from "vue";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import {
  listAttachments,
  listMyInbox,
  markRead,
  openAttachment,
  unreadCount,
  type InboxAttachment,
  type InboxEntry,
} from "../../api/inbox";
import { messageForHttpError, toastHttpError } from "../../api/platform";
import { readTenantId } from "../../api/tenant";

type InboxEntryWithFiles = InboxEntry & { attachments: InboxAttachment[] };

const entries = ref<InboxEntryWithFiles[]>([]);
const unread = ref(0);
const error = ref("");
const busy = ref(false);
const loading = ref(false);
const signedIn = ref(isSignedIn());
const loginHref = loginPageUrl("/pages/inbox/inbox");

async function withAttachments(item: InboxEntry): Promise<InboxEntryWithFiles> {
  try {
    const attachments = await listAttachments(undefined, item.entryId);
    return { ...item, attachments };
  } catch {
    return { ...item, attachments: [] };
  }
}

async function refresh() {
  if (!signedIn.value) return;
  if (!readTenantId()) {
    error.value = "还没有选择组织，请先登录";
    return;
  }
  busy.value = true;
  loading.value = true;
  error.value = "";
  try {
    unread.value = await unreadCount();
    const listed = await listMyInbox({});
    entries.value = await Promise.all(listed.map(withAttachments));
  } catch (e) {
    entries.value = [];
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
    loading.value = false;
  }
}

async function onMark(entryId: string) {
  busy.value = true;
  try {
    await markRead(undefined, entryId);
    await refresh();
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onOpen(entryId: string, file: InboxAttachment) {
  busy.value = true;
  error.value = "";
  try {
    await openAttachment(undefined, entryId, file);
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
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
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.row { display: flex; align-items: center; gap: 16rpx; flex-wrap: wrap; }
.badge { font-size: 24rpx; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
.attachments { display: flex; flex-direction: column; gap: 8rpx; }
.file-name { font-size: 26rpx; color: #333; flex: 1; }
</style>
