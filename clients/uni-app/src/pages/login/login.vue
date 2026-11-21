<template>
  <view class="page">
    <view class="card">
      <text class="title">登录 / Sign in (WP2-6)</text>
      <text class="body">
        本页只调用终端认证接口 /api/v1/end-user/auth/**，不再走操作员登录或微信回调。
        This page calls end-user auth only — not operator /api/v1/auth/**.
      </text>

      <text class="label">业务租户 / Business tenantId（必填，不能是 platform）</text>
      <input
        class="input"
        v-model="tenantId"
        placeholder="existing ACTIVE tenantId"
        @blur="onTenantBlur"
      />
      <text class="hint" v-if="tenantHint">{{ tenantHint }}</text>

      <text class="label">手机号登录（注册或登录）/ Phone register-or-login</text>
      <input class="input" v-model="phone" type="number" placeholder="phone" />
      <view class="row">
        <input class="input flex" v-model="loginCode" placeholder="verification code" />
        <button size="mini" :disabled="busy" @click="onRequestLoginCode">
          获取验证码 / Get code
        </button>
      </view>
      <text class="hint" v-if="devCode">开发验证码 / dev code: {{ devCode }}（仅本地揭示）</text>
      <button size="mini" type="primary" :disabled="busy" @click="onPhoneLogin">
        登录 / Sign in
      </button>

      <!-- #ifdef MP-WEIXIN -->
      <button size="mini" :disabled="busy" @click="onWeChatMini">
        微信小程序登录 / WeChat mini login
      </button>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <text class="muted">
        微信小程序登录仅在小程序运行时可用（需要 uni.login jsCode）。H5 请用手机号。
        WeChat mini login is hidden on H5 — jsCode is unavailable here.
      </text>
      <!-- #endif -->

      <button size="mini" v-if="signedIn" @click="onLogout">退出 / Sign out</button>

      <text class="muted" v-if="status">{{ status }}</text>
      <navigator url="/pages/index/index" class="link">← 首页 / Home</navigator>
      <navigator url="/pages/inbox/inbox" class="link">站内信 / Inbox →</navigator>
    </view>

    <view class="card" v-if="signedIn">
      <text class="title">绑定 / 解绑 / Bind and unbind</text>
      <text class="body">
        需要已登录的终端令牌。解绑最后一个认证器会返回 409 last-authenticator。
        Requires a signed-in end-user token. Unbinding the last authenticator returns 409.
      </text>

      <text class="label">手机号 / Phone</text>
      <input class="input" v-model="bindPhoneNumber" type="number" placeholder="phone" />
      <view class="row">
        <input class="input flex" v-model="bindCode" placeholder="verification code" />
        <button size="mini" :disabled="busy" @click="onRequestBindCode('bind')">
          绑定验证码 / Bind code
        </button>
        <button size="mini" :disabled="busy" @click="onRequestBindCode('unbind')">
          解绑验证码 / Unbind code
        </button>
      </view>
      <text class="hint" v-if="bindDevCode">开发验证码 / dev code: {{ bindDevCode }}</text>
      <view class="row">
        <button size="mini" :disabled="busy" @click="onBindPhone">绑定手机 / Bind phone</button>
        <button size="mini" :disabled="busy" @click="onUnbindPhone">解绑手机 / Unbind phone</button>
      </view>

      <!-- #ifdef MP-WEIXIN -->
      <view class="row">
        <button size="mini" :disabled="busy" @click="onBindWeChat">绑定微信 / Bind WeChat</button>
        <button size="mini" :disabled="busy" @click="onUnbindWeChat">解绑微信 / Unbind WeChat</button>
      </view>
      <!-- #endif -->
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Login — phone (H5 + MP) + WeChat mini jsCode (MP only) (WP2-6).
 * 登录：手机号（H5 与小程序）+ 小程序 jsCode（仅小程序）。
 */
import { ref, onMounted } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import {
  PHONE_PURPOSE_BIND,
  PHONE_PURPOSE_LOGIN,
  PHONE_PURPOSE_UNBIND,
  bindPhone,
  bindWeChat,
  isSignedIn,
  loginWithPhone,
  loginWithWeChatMini,
  logoutAndClear,
  requestPhoneCode,
  unbindPhone,
  unbindWeChat,
} from "../../api/auth";
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
const bindPhoneNumber = ref("");
const bindCode = ref("");
const bindDevCode = ref("");
const status = ref("");
const busy = ref(false);
const signedIn = ref(isSignedIn());

function syncTenantHint() {
  const raw = tenantId.value.trim();
  if (!raw) {
    tenantHint.value = "登录前须填写已存在且 ACTIVE 的业务租户 / an existing ACTIVE tenant is required";
    return;
  }
  if (isReservedTenant(raw)) {
    tenantHint.value = "不能使用保留租户 platform / must not use reserved tenant platform";
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

async function onRequestLoginCode() {
  busy.value = true;
  status.value = "正在请求验证码… / requesting code…";
  devCode.value = "";
  try {
    const issued = await requestPhoneCode(phone.value, PHONE_PURPOSE_LOGIN);
    devCode.value = issued.devCode || "";
    status.value = devCode.value
      ? "验证码已签发（本地揭示）/ code issued (dev reveal)"
      : "验证码已发送 / code sent";
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function onPhoneLogin() {
  busy.value = true;
  status.value = "登录中… / signing in…";
  try {
    const tenant = rememberTenant();
    const tokens = await loginWithPhone(phone.value, loginCode.value, tenant);
    signedIn.value = true;
    status.value = `已登录 / signed in (eu.at. len ${tokens.accessToken.length})`;
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
        status.value = "uni.login 无 code / no code";
        busy.value = false;
        return;
      }
      try {
        const tokens = await loginWithWeChatMini(code, tenant);
        signedIn.value = true;
        status.value = `微信登录成功 / WeChat ok (eu.at. len ${tokens.accessToken.length})`;
      } catch (e) {
        status.value = e instanceof Error ? e.message : String(e);
      } finally {
        busy.value = false;
      }
    },
    fail: (err) => {
      status.value = `uni.login 失败 / failed: ${JSON.stringify(err)}`;
      busy.value = false;
    },
  });
  // #endif
  // #ifndef MP-WEIXIN
  status.value =
    "非小程序运行时：请用上方手机号登录，或用微信开发者工具跑 mp-weixin。 / Not mp-weixin; use phone login or WeChat DevTools.";
  busy.value = false;
  // #endif
}

async function onRequestBindCode(kind: "bind" | "unbind") {
  busy.value = true;
  bindDevCode.value = "";
  const purpose = kind === "unbind" ? PHONE_PURPOSE_UNBIND : PHONE_PURPOSE_BIND;
  try {
    const issued = await requestPhoneCode(bindPhoneNumber.value, purpose);
    bindDevCode.value = issued.devCode || "";
    status.value = bindDevCode.value
      ? "验证码已签发（本地揭示）/ code issued (dev reveal)"
      : "验证码已发送 / code sent";
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function onBindPhone() {
  busy.value = true;
  try {
    await bindPhone(bindPhoneNumber.value, bindCode.value);
    status.value = "手机号已绑定 / phone bound";
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function onUnbindPhone() {
  busy.value = true;
  try {
    await unbindPhone(bindPhoneNumber.value, bindCode.value);
    status.value = "手机号已解绑 / phone unbound";
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

function runWeChatJsCode(action: (jsCode: string) => Promise<void>, okMessage: string) {
  busy.value = true;
  status.value = "";
  // #ifdef MP-WEIXIN
  uni.login({
    provider: "weixin",
    success: async (res) => {
      const code = res.code || "";
      if (!code) {
        status.value = "uni.login 无 code / no code";
        busy.value = false;
        return;
      }
      try {
        await action(code);
        status.value = okMessage;
      } catch (e) {
        status.value = e instanceof Error ? e.message : String(e);
      } finally {
        busy.value = false;
      }
    },
    fail: (err) => {
      status.value = `uni.login 失败 / failed: ${JSON.stringify(err)}`;
      busy.value = false;
    },
  });
  // #endif
  // #ifndef MP-WEIXIN
  status.value = "微信绑定仅在小程序可用 / WeChat bind is mini-program only";
  busy.value = false;
  // #endif
}

function onBindWeChat() {
  runWeChatJsCode((jsCode) => bindWeChat(jsCode), "微信已绑定 / WeChat bound");
}

function onUnbindWeChat() {
  runWeChatJsCode((jsCode) => unbindWeChat(jsCode), "微信已解绑 / WeChat unbound");
}

async function onLogout() {
  busy.value = true;
  try {
    await logoutAndClear();
    signedIn.value = false;
    status.value = "已退出 / signed out";
  } catch (e) {
    signedIn.value = false;
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

onLoad((query) => {
  const fromQuery = applyTenantFromQuery(query as Record<string, unknown>);
  if (fromQuery) tenantId.value = fromQuery;
  else tenantId.value = readTenantId() || tenantId.value;
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
.body { font-size: 26rpx; color: #333; }
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
