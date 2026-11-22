<template>
  <view class="page">
    <view class="card">
      <text class="title">问卷</text>
      <text class="muted">这里列出需要您填写的问卷。有填写码也可以直接打开。</text>
      <view class="row" v-if="signedIn">
        <button size="mini" type="primary" :disabled="busy" @click="refreshList">
          {{ busy ? "正在加载…" : "刷新列表" }}
        </button>
      </view>
      <navigator v-if="!signedIn" :url="loginHref" class="link">登录后查看需要您填写的问卷</navigator>
      <text class="warn" v-if="listError">{{ listError }}</text>
      <text class="muted" v-if="signedIn && busy">正在加载问卷…</text>
      <view v-for="row in published" :key="row.questionnaireId" class="row-item">
        <text class="entry-title">{{ row.title }}</text>
        <button size="mini" type="primary" @click="openAuth(row.questionnaireId!)">去填写</button>
      </view>
      <text class="muted" v-if="signedIn && !published.length && !listError && listedOnce && !busy">
        暂时没有需要填写的问卷。
      </text>
    </view>

    <view class="card">
      <text class="label">我有填写码</text>
      <input class="input" v-model="tokenId" placeholder="请输入填写码" />
      <button size="mini" :disabled="!tokenId.trim()" @click="openFill">用填写码打开</button>
    </view>

  </view>
</template>

<script setup lang="ts">
/**
 * Questionnaire list: end-user AUTH list + optional public collect token.
 * 问卷列表：登录后的认证问卷 + 可选公开填写码。
 */
import { ref, onMounted } from "vue";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import { listAuthenticated, type QuestionnaireDefinition } from "../../api/questionnaire";
import { messageForHttpError, toastHttpError } from "../../api/platform";
import { readTenantId } from "../../api/tenant";

const tokenId = ref("");
const published = ref<QuestionnaireDefinition[]>([]);
const listError = ref("");
const busy = ref(false);
const listedOnce = ref(false);
const signedIn = ref(isSignedIn());
const loginHref = loginPageUrl("/pages/questionnaire/list");

function openFill() {
  const t = tokenId.value.trim();
  if (!t) return;
  uni.navigateTo({
    url: `/pages/questionnaire/fill?token=${encodeURIComponent(t)}`,
  });
}

function openAuth(id: string) {
  uni.navigateTo({
    url: `/pages/questionnaire/fill?id=${encodeURIComponent(id)}`,
  });
}

async function refreshList() {
  if (!signedIn.value) return;
  if (!readTenantId()) {
    listError.value = "还没有选择组织，请先登录";
    return;
  }
  busy.value = true;
  listError.value = "";
  listedOnce.value = true;
  try {
    published.value = await listAuthenticated();
  } catch (e) {
    published.value = [];
    listError.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  signedIn.value = isSignedIn();
  if (signedIn.value) void refreshList();
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
.input { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.row { display: flex; align-items: center; gap: 16rpx; flex-wrap: wrap; }
.row-item { display: flex; flex-direction: column; gap: 6rpx; padding: 8rpx 0; border-top: 1px solid #eee; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
