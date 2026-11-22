<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的资料</text>
      <text class="muted">登录后可以查看和修改您的称呼，并管理登录方式。</text>
      <navigator :url="loginHref" class="link">去登录</navigator>
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
      <text class="title">登录方式</text>
      <text class="muted">您可以绑定或解绑手机号。至少保留一种登录方式，否则将无法再次登录。</text>
      <text class="label">手机号</text>
      <input class="input" v-model="bindPhoneNumber" type="number" placeholder="请输入要绑定或解绑的手机号" />
      <view class="row">
        <input class="input flex" v-model="bindCode" placeholder="请输入验证码" />
        <button size="mini" :disabled="busy" @click="onRequestBindCode('bind')">获取绑定验证码</button>
        <button size="mini" :disabled="busy" @click="onRequestBindCode('unbind')">获取解绑验证码</button>
      </view>
      <text class="hint" v-if="bindDevCode">本次验证码（仅本地调试显示）：{{ bindDevCode }}</text>
      <view class="row">
        <button size="mini" :disabled="busy" @click="onBindPhone">绑定手机号</button>
        <button size="mini" :disabled="busy" @click="onUnbindPhone">解绑手机号</button>
      </view>
      <!-- #ifdef MP-WEIXIN -->
      <view class="row">
        <button size="mini" :disabled="busy" @click="onBindWeChat">绑定微信</button>
        <button size="mini" :disabled="busy" @click="onUnbindWeChat">解绑微信</button>
      </view>
      <!-- #endif -->
      <text class="muted" v-if="bindStatus">{{ bindStatus }}</text>
    </view>

    <view class="card" v-if="signedIn">
      <navigator url="/pages/consent/consent" class="link">管理我的同意</navigator>
      <navigator url="/pages/export/export" class="link">申请导出我的资料</navigator>
      <button size="mini" :disabled="busy" @click="onLogout">退出登录</button>
    </view>

    <navigator url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * Profile: display name, authenticators, and logout — 资料：称呼、登录方式与退出。
 */
import { computed, ref, onMounted } from "vue";
import {
  PHONE_PURPOSE_BIND,
  PHONE_PURPOSE_UNBIND,
  bindPhone,
  bindWeChat,
  isSignedIn,
  logoutAndClear,
  requestPhoneCode,
  unbindPhone,
  unbindWeChat,
} from "../../api/auth";
import { getProfile, updateProfile } from "../../api/profile";
import { goToLogin, messageForHttpError, toastHttpError } from "../../api/platform";
import { loginPageUrl } from "../../api/shell-path";

const signedIn = ref(isSignedIn());
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const saved = ref(false);
const subjectName = ref("");
const bindPhoneNumber = ref("");
const bindCode = ref("");
const bindDevCode = ref("");
const bindStatus = ref("");
const loginHref = computed(() => loginPageUrl("/pages/profile/profile"));

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

async function onRequestBindCode(kind: "bind" | "unbind") {
  busy.value = true;
  bindDevCode.value = "";
  bindStatus.value = "正在获取验证码…";
  const purpose = kind === "unbind" ? PHONE_PURPOSE_UNBIND : PHONE_PURPOSE_BIND;
  try {
    const issued = await requestPhoneCode(bindPhoneNumber.value, purpose);
    bindDevCode.value = issued.devCode || "";
    bindStatus.value = bindDevCode.value ? "验证码已签发。" : "验证码已发送，请查看手机短信。";
  } catch (e) {
    bindStatus.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onBindPhone() {
  busy.value = true;
  try {
    await bindPhone(bindPhoneNumber.value, bindCode.value);
    bindStatus.value = "手机号已绑定";
  } catch (e) {
    bindStatus.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onUnbindPhone() {
  busy.value = true;
  try {
    await unbindPhone(bindPhoneNumber.value, bindCode.value);
    bindStatus.value = "手机号已解绑";
  } catch (e) {
    bindStatus.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

function runWeChatJsCode(action: (jsCode: string) => Promise<void>, okMessage: string) {
  busy.value = true;
  bindStatus.value = "";
  // #ifdef MP-WEIXIN
  uni.login({
    provider: "weixin",
    success: async (res) => {
      const code = res.code || "";
      if (!code) {
        bindStatus.value = "没有拿到微信登录凭证，请重试";
        busy.value = false;
        return;
      }
      try {
        await action(code);
        bindStatus.value = okMessage;
      } catch (e) {
        bindStatus.value = messageForHttpError(e);
        toastHttpError(e);
      } finally {
        busy.value = false;
      }
    },
    fail: () => {
      bindStatus.value = "微信操作没有完成，请重试";
      busy.value = false;
    },
  });
  // #endif
  // #ifndef MP-WEIXIN
  bindStatus.value = "绑定或解绑微信只在小程序中提供。";
  busy.value = false;
  // #endif
}

function onBindWeChat() {
  runWeChatJsCode((jsCode) => bindWeChat(jsCode), "微信已绑定");
}

function onUnbindWeChat() {
  runWeChatJsCode((jsCode) => unbindWeChat(jsCode), "微信已解绑");
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
.hint { color: #b45309; font-size: 22rpx; }
.ok { color: #15803d; font-size: 24rpx; }
.input { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.row { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.flex { flex: 1; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
