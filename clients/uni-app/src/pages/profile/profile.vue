<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的资料</text>
      <text class="muted">登录后可以查看和修改您的称呼。</text>
      <navigator url="/pages/login/login" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的资料</text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <view v-if="!loading">
        <text class="label">您的称呼</text>
        <input class="input" v-model="subjectName" maxlength="256" placeholder="请输入姓名或昵称" />
        <button size="mini" type="primary" :disabled="busy || !subjectName.trim()" @click="onSave">
          {{ busy ? "正在保存…" : "保存" }}
        </button>
        <text class="ok" v-if="saved">已保存</text>
      </view>
    </view>

    <view class="card" v-if="signedIn">
      <button size="mini" :disabled="busy" @click="onLogout">退出登录</button>
    </view>

    <navigator url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * Profile: read/update display name and logout — 资料：查看/修改称呼并退出。
 */
import { ref, onMounted } from "vue";
import { isSignedIn, logoutAndClear } from "../../api/auth";
import { getProfile, updateProfile } from "../../api/profile";
import { goToLogin, messageForHttpError, toastHttpError } from "../../api/platform";

const signedIn = ref(isSignedIn());
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const saved = ref(false);
const subjectName = ref("");

async function load() {
  if (!signedIn.value) return;
  loading.value = true;
  error.value = "";
  try {
    const profile = await getProfile();
    subjectName.value = profile.subjectName || "";
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    loading.value = false;
  }
}

async function onSave() {
  busy.value = true;
  error.value = "";
  saved.value = false;
  try {
    const profile = await updateProfile(subjectName.value);
    subjectName.value = profile.subjectName || subjectName.value.trim();
    saved.value = true;
    uni.showToast({ title: "称呼已更新", icon: "none" });
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onLogout() {
  busy.value = true;
  try {
    await logoutAndClear();
  } finally {
    busy.value = false;
    goToLogin();
  }
}

onMounted(() => {
  signedIn.value = isSignedIn();
  void load();
});
</script>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 20rpx; }
.card {
  background: #fff; border-radius: 12rpx; padding: 24rpx;
  display: flex; flex-direction: column; gap: 12rpx; border: 1px solid #e5e5e5;
}
.title { font-size: 32rpx; font-weight: 600; }
.label { color: #888; font-size: 22rpx; }
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.ok { color: #15803d; font-size: 24rpx; }
.input { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
