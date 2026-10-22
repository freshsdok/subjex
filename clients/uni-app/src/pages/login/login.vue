<template>
  <view class="page">
    <view class="card">
      <text class="title">登录 / Sign in (#3a)</text>
      <text class="body">
        H5：口令 → POST /api/v1/auth/login → Bearer。小程序：jsCode →
        POST /api/v1/auth/idp/wechat/callback（须预先 IdP 绑定）。
      </text>
      <text class="body">
        H5: password login issues opaque Bearer. Mini-program: jsCode via WeChat IdP
        (link-required, no auto-provision). Not the operator Redis session.
      </text>

      <text class="label">口令登录（H5 / 开发）/ Password (H5 / dev)</text>
      <input class="input" v-model="loginName" placeholder="loginName" />
      <input class="input" v-model="password" password placeholder="password" />
      <button size="mini" type="primary" :disabled="busy" @click="onPasswordLogin">
        登录 / Sign in
      </button>

      <button
        size="mini"
        :disabled="!wxStubAllowed || busy"
        :class="{ locked: !wxStubAllowed }"
        @click="onWeChatMini"
      >
        微信小程序 jsCode 登录 / WeChat mini login
      </button>
      <text v-if="!wxStubAllowed" class="hint">
        按钮已禁用（Manifest）/ disabled by Manifest
      </text>

      <button size="mini" v-if="signedIn" @click="onLogout">退出 / Sign out</button>

      <text class="muted" v-if="status">{{ status }}</text>
      <text class="muted">{{ manifestStatus }}</text>
      <navigator url="/pages/index/index" class="link">← 首页 / Home</navigator>
      <navigator url="/pages/inbox/inbox" class="link">站内信 / Inbox →</navigator>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Login — password (H5) + wechat:mini jsCode callback (#3a).
 * 登录：H5 口令 + 小程序 jsCode 回调。
 */
import { ref, computed, onMounted } from "vue";
import { readBearer } from "../../api/auth-token";
import {
  clearTokens,
  isSignedIn,
  loginWithPassword,
  loginWithWeChatMini,
} from "../../api/auth";
import {
  BUTTON_SHELL_WX_LOGIN_STUB,
  buttonAllowed,
  fetchUiPermissions,
  type UiPermissionManifest,
} from "../../api/ui-permissions";

const loginName = ref("platform-operator");
const password = ref("change-me");
const status = ref("");
const busy = ref(false);
const signedIn = ref(isSignedIn());
const manifest = ref<UiPermissionManifest | undefined>(undefined);
const manifestError = ref<string | null>(null);

const wxStubAllowed = computed(() =>
  buttonAllowed(manifest.value, BUTTON_SHELL_WX_LOGIN_STUB, true)
);

const manifestStatus = computed(() => {
  if (manifestError.value) return `Manifest: ${manifestError.value}`;
  if (!manifest.value) return "Manifest: （未加载）";
  return `Manifest: client=${manifest.value.client}`;
});

async function onPasswordLogin() {
  busy.value = true;
  status.value = "登录中… / signing in…";
  try {
    const tokens = await loginWithPassword(loginName.value.trim(), password.value);
    signedIn.value = true;
    status.value = `已登录 / signed in (token len ${tokens.accessToken.length})`;
    void refreshManifest();
  } catch (e) {
    status.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

function onWeChatMini() {
  if (!wxStubAllowed.value) return;
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
        const tokens = await loginWithWeChatMini(code);
        signedIn.value = true;
        status.value = `微信登录成功 / WeChat ok (token len ${tokens.accessToken.length})`;
        void refreshManifest();
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
    "非小程序运行时：请用上方口令登录，或真机微信开发者工具跑 mp-weixin。 / Not mp-weixin; use password or WeChat DevTools.";
  busy.value = false;
  // #endif
}

function onLogout() {
  clearTokens();
  signedIn.value = false;
  status.value = "已退出 / signed out";
}

async function refreshManifest() {
  manifestError.value = null;
  try {
    manifest.value = await fetchUiPermissions(readBearer());
  } catch (e) {
    manifest.value = undefined;
    const msg = e instanceof Error ? e.message : String(e);
    manifestError.value = msg.includes("401")
      ? "需先登录 / sign in first"
      : msg.slice(0, 120);
  }
}

onMounted(() => {
  signedIn.value = isSignedIn();
  void refreshManifest();
});
</script>

<style scoped>
.page { padding: 24rpx; }
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
.muted { font-size: 24rpx; color: #666; }
.hint { color: #b45309; font-size: 22rpx; }
.link { color: #2563eb; }
button.locked { opacity: 0.5; }
</style>
