<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的资料</text>
      <text class="muted">登录后可以查看和修改您的称呼、头像和邮箱，并管理登录方式。</text>
      <navigator :url="loginHref" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的资料</text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <view v-if="!loading">
        <text class="label">头像</text>
        <view class="row">
          <image v-if="avatarUrl" class="avatar" :src="avatarUrl" mode="aspectFill" />
          <view v-else class="avatar avatar-empty">
            <text class="avatar-letter">{{ avatarLetter }}</text>
          </view>
          <button size="mini" :disabled="busy" @click="onChangeAvatar">更换头像</button>
          <button size="mini" :disabled="busy || !avatarObjectId" @click="onClearAvatar">移除头像</button>
        </view>
        <text class="muted">请选择一张照片。较大的图片会按现有方式分段上传。</text>

        <text class="label">您的称呼</text>
        <input class="input" v-model="subjectName" maxlength="256" placeholder="请输入姓名或昵称" />
        <button size="mini" type="primary" :disabled="busy || !subjectName.trim()" @click="onSave">
          {{ busy ? "正在保存…" : "保存称呼" }}
        </button>
        <text class="ok" v-if="saved">称呼已保存</text>

        <text class="label">邮箱</text>
        <text class="muted" v-if="verifiedEmail">当前邮箱：{{ verifiedEmail }}（已验证）</text>
        <text class="muted" v-else>还没有已验证的邮箱。</text>
        <text class="hint" v-if="pendingEmail">待验证：{{ pendingEmail }}。验证通过前不会替换已有邮箱。</text>
        <input class="input" v-model="emailDraft" maxlength="256" placeholder="请输入要验证的邮箱" />
        <view class="row">
          <input class="input flex" v-model="emailCode" placeholder="请输入验证码" />
          <button size="mini" :disabled="busy" @click="onRequestEmailCode">获取验证码</button>
          <button size="mini" :disabled="busy" @click="onVerifyEmail">验证并保存</button>
        </view>
        <text class="hint" v-if="emailDevCode">本次验证码（仅本地调试显示）：{{ emailDevCode }}</text>
        <text class="ok" v-if="emailStatusOk">{{ emailStatus }}</text>
        <text class="muted" v-else-if="emailStatus">{{ emailStatus }}</text>
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
      <navigator url="/pages/payment/payment" class="link">查看我的支付</navigator>
      <button size="mini" :disabled="busy" @click="onLogout">退出登录</button>
    </view>

  </view>
</template>

<script setup lang="ts">
/**
 * Profile: display name, avatar, verified email, authenticators, and logout.
 * 资料：称呼、头像、须验证的邮箱、登录方式与退出。
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
import { uploadObject } from "../../api/objects";
import {
  clearAvatar,
  getProfile,
  pickAvatar,
  requestEmailCode,
  setAvatar,
  updateProfile,
  verifyEmail,
  type EndUserProfile,
} from "../../api/profile";
import { goToLogin, messageForHttpError, toastHttpError } from "../../api/platform";
import { loginPageUrl } from "../../api/shell-path";

const signedIn = ref(isSignedIn());
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const saved = ref(false);
const subjectName = ref("");
const avatarObjectId = ref("");
const avatarUrl = ref("");
const verifiedEmail = ref("");
const pendingEmail = ref("");
const emailDraft = ref("");
const emailCode = ref("");
const emailDevCode = ref("");
const emailStatus = ref("");
const emailStatusOk = ref(false);
const bindPhoneNumber = ref("");
const bindCode = ref("");
const bindDevCode = ref("");
const bindStatus = ref("");
const loginHref = computed(() => loginPageUrl("/pages/profile/profile"));
const avatarLetter = computed(() => {
  const name = subjectName.value.trim();
  return name ? name.slice(0, 1) : "我";
});

function applyProfile(profile: EndUserProfile) {
  subjectName.value = profile.subjectName || subjectName.value;
  avatarObjectId.value = profile.avatarObjectId || "";
  avatarUrl.value = profile.avatarDownload?.url || "";
  verifiedEmail.value = profile.email || "";
  pendingEmail.value = profile.pendingEmail || "";
  if (profile.pendingEmail && !emailDraft.value) {
    emailDraft.value = profile.pendingEmail;
  } else if (profile.email && !emailDraft.value) {
    emailDraft.value = profile.email;
  }
}

async function load() {
  if (!signedIn.value) return;
  loading.value = true;
  error.value = "";
  try {
    applyProfile(await getProfile());
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
    applyProfile(await updateProfile(subjectName.value));
    saved.value = true;
    uni.showToast({ title: "称呼已更新", icon: "none" });
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onChangeAvatar() {
  busy.value = true;
  error.value = "";
  try {
    const file = await pickAvatar();
    const uploaded = await uploadObject(file);
    if (!uploaded.objectId) {
      throw new Error("上传已完成，但没有拿到头像编号，请稍后重试");
    }
    applyProfile(await setAvatar(uploaded.objectId));
    uni.showToast({ title: "头像已更新", icon: "none" });
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onClearAvatar() {
  busy.value = true;
  error.value = "";
  try {
    applyProfile(await clearAvatar());
    uni.showToast({ title: "头像已移除", icon: "none" });
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onRequestEmailCode() {
  busy.value = true;
  emailDevCode.value = "";
  emailStatusOk.value = false;
  emailStatus.value = "正在获取验证码…";
  try {
    const issued = await requestEmailCode(emailDraft.value);
    emailDevCode.value = issued.devCode || "";
    emailStatus.value = emailDevCode.value
      ? "验证码已签发。请在验证通过后再把它当作您的邮箱。"
      : "验证码已发送，请查看您的邮箱。验证通过前不会替换已有邮箱。";
  } catch (e) {
    emailStatus.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onVerifyEmail() {
  busy.value = true;
  emailStatusOk.value = false;
  try {
    applyProfile(await verifyEmail(emailDraft.value, emailCode.value));
    emailCode.value = "";
    emailDevCode.value = "";
    emailStatusOk.value = true;
    emailStatus.value = "邮箱已验证并保存";
    uni.showToast({ title: "邮箱已验证", icon: "none" });
  } catch (e) {
    emailStatus.value = messageForHttpError(e);
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
.avatar {
  width: 120rpx; height: 120rpx; border-radius: 60rpx;
  background: #e5e7eb; border: 1px solid #ddd;
}
.avatar-empty { display: flex; align-items: center; justify-content: center; }
.avatar-letter { font-size: 40rpx; color: #444; }
</style>
