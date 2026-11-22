<template>
  <view class="page">
    <view class="card">
      <text class="title">登录</text>
      <text class="muted">使用手机号登录您所在的组织。在微信小程序里也可以用微信登录。</text>

      <text class="label">组织编号</text>
      <input
        class="input"
        v-model="tenantId"
        placeholder="请填写组织编号"
        @blur="onTenantBlur"
      />
      <text class="hint" v-if="tenantHint">{{ tenantHint }}</text>

      <text class="label">手机号</text>
      <input class="input" v-model="phone" type="number" placeholder="请输入手机号" />
      <view class="row">
        <input class="input flex" v-model="loginCode" placeholder="请输入验证码" />
        <button size="mini" :disabled="busy" @click="onRequestLoginCode">
          获取验证码
        </button>
      </view>
      <text class="hint" v-if="devCode">本次验证码（仅本地调试显示）：{{ devCode }}</text>
      <button size="mini" type="primary" :disabled="busy" @click="onPhoneLogin">
        登录
      </button>

      <!-- #ifdef MP-WEIXIN -->
      <button size="mini" :disabled="busy" @click="onWeChatMini">微信登录</button>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <text class="muted">微信登录只在小程序中提供。当前请使用手机号登录。</text>
      <!-- #endif -->

      <text class="muted" v-if="status">{{ status }}</text>
      <navigator open-type="switchTab" url="/pages/index/index" class="link">返回首页</navigator>
    </view>

    <view class="card" v-if="signedIn">
      <text class="muted">您已登录。</text>
      <button size="mini" type="primary" :disabled="busy" @click="goAfterLogin">进入首页</button>
      <button size="mini" :disabled="busy" @click="onLogout">退出登录</button>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Login — phone (H5 + mini-program) and WeChat jsCode (mini-program only).
 * 登录：手机号（H5 与小程序）+ 小程序微信登录。成功后进入首页或返回原功能页。
 */
import { ref, onMounted } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import {
  PHONE_PURPOSE_LOGIN,
  isSignedIn,
  loginWithPhone,
  loginWithWeChatMini,
  logoutAndClear,
  requestPhoneCode,
} from "../../api/auth";
import { openShellPage } from "../../api/shell-path";
import {
  applyTenantFromQuery,
  isReservedTenant,
  readTenantId,
  writeTenantId,
} from "../../api/tenant";

const tenantId = ref(readTenantId() || "");
const tenantHint = ref("");
const phone = ref("");
const loginCode = ref("");
const devCode = ref("");
const status = ref("");
const busy = ref(false);
const signedIn = ref(isSignedIn());
const redirect = ref("");

function syncTenantHint() {
  const raw = tenantId.value.trim();
  if (!raw) {
    tenantHint.value = "登录前请填写您所在组织的编号";
    return;
  }
  if (isReservedTenant(raw)) {
    tenantHint.value = "请填写您所在组织的编号，不要使用内部保留编号";
    return;
  }
  tenantHint.value = "";
}

function onTenantBlur() {
  syncTenantHint();
  const raw = tenantId.value.trim();
  if (raw && !isReservedTenant(raw)) {
    try {
      writeTenantId(raw);
    } catch (e) {
      tenantHint.value = e instanceof Error ? e.message : String(e);
    }
  }
}

function rememberTenant(): string {
  const id = writeTenantId(tenantId.value);
  tenantId.value = id;
  tenantHint.value = "";
  return id;
}

function goAfterLogin() {
  openShellPage(redirect.value);
}

async function onRequestLoginCode() {
  busy.value = true;
  status.value = "正在获取验证码…";
  devCode.value = "";
  try {
    const issued = await requestPhoneCode(phone.value, PHONE_PURPOSE_LOGIN);
    devCode.value = issued.devCode || "";
    status.value = devCode.value ? "验证码已签发。" : "验证码已发送，请查看手机短信。";
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function onPhoneLogin() {
  busy.value = true;
  status.value = "正在登录…";
  try {
    const tenant = rememberTenant();
    await loginWithPhone(phone.value, loginCode.value, tenant);
    signedIn.value = true;
    goAfterLogin();
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

function onWeChatMini() {
  busy.value = true;
  status.value = "";
  // #ifdef MP-WEIXIN
  let tenant: string;
  try {
    tenant = rememberTenant();
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
    busy.value = false;
    return;
  }
  uni.login({
    provider: "weixin",
    success: async (res) => {
      const code = res.code || "";
      if (!code) {
        status.value = "没有拿到微信登录凭证，请重试";
        busy.value = false;
        return;
      }
      try {
        await loginWithWeChatMini(code, tenant);
        signedIn.value = true;
        goAfterLogin();
      } catch (e) {
        status.value = e instanceof Error ? e.message : String(e);
      } finally {
        busy.value = false;
      }
    },
    fail: () => {
      status.value = "微信登录没有完成，请重试";
      busy.value = false;
    },
  });
  // #endif
  // #ifndef MP-WEIXIN
  status.value = "当前不在小程序中，请使用手机号登录。";
  busy.value = false;
  // #endif
}

async function onLogout() {
  busy.value = true;
  try {
    await logoutAndClear();
    signedIn.value = false;
    status.value = "已退出登录";
  } catch (e) {
    signedIn.value = false;
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

onLoad((query) => {
  const bag = (query || {}) as Record<string, unknown>;
  const fromQuery = applyTenantFromQuery(bag);
  if (fromQuery) tenantId.value = fromQuery;
  else tenantId.value = readTenantId() || tenantId.value;
  const rawRedirect = bag.redirect;
  redirect.value = typeof rawRedirect === "string" ? rawRedirect : "";
  syncTenantHint();
});

onMounted(() => {
  signedIn.value = isSignedIn();
  if (!tenantId.value) tenantId.value = readTenantId() || "";
  syncTenantHint();
});
</script>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 20rpx; }
.card {
  background: #fff; border-radius: 12rpx; padding: 28rpx;
  display: flex; flex-direction: column; gap: 16rpx; border: 1px solid #e5e5e5;
}
.title { font-size: 32rpx; font-weight: 600; }
.label { font-size: 22rpx; color: #888; margin-top: 8rpx; }
.input {
  border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx 16rpx; font-size: 26rpx;
}
.flex { flex: 1; }
.row { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.muted { font-size: 24rpx; color: #666; }
.hint { color: #b45309; font-size: 22rpx; }
.link { color: #2563eb; }
</style>
