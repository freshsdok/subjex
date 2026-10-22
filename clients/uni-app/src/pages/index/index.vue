<template>
  <view class="page">
    <view class="card">
      <text class="title">subjex 终端壳 / End-user shell</text>
      <text class="muted">
        本工程是 H5 + 微信小程序（uni-app），不是操作员控制台 `web/`。
        This is H5 + WeChat mini-program (uni-app), not the operator console.
      </text>
    </view>

    <view class="card">
      <text class="label">运行平台 / Platform</text>
      <text class="value">{{ platformLabel }}</text>
      <text class="label">API 根 / API base</text>
      <text class="value mono">{{ base }}</text>
      <text class="label">登录状态 / Auth</text>
      <text class="value">{{ signedIn ? "已登录 Bearer / signed in" : "未登录 / signed out" }}</text>
      <text class="label">存活探针 / Liveness</text>
      <text class="value">{{ livenessText }}</text>
      <button
        size="mini"
        type="primary"
        :disabled="!reprobeAllowed"
        :class="{ locked: !reprobeAllowed }"
        @click="onReprobe"
      >
        重新探测 / Re-probe
      </button>
      <text v-if="!reprobeAllowed" class="hint">
        按钮已禁用（Manifest btn.shell.reprobe）/ disabled by Manifest
      </text>
    </view>

    <view class="card">
      <text class="label">UI 权限 Manifest / UI permissions (PC-5)</text>
      <text class="value">{{ manifestStatus }}</text>
      <view v-for="item in navItems" :key="item.id" class="nav-row">
        <navigator
          v-if="item.allowed && item.href"
          :url="item.href"
          class="link"
        >
          {{ item.label }}
        </navigator>
        <text
          v-else
          class="link locked"
          :title="item.hint || 'locked'"
        >
          {{ item.label }}（置灰 / grey）
          <text v-if="item.hint" class="hint"> — {{ item.hint }}</text>
        </text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Shell home: platform probe + UI permission Manifest nav/buttons (PC-5).
 * 壳首页：平台探针 + UI 权限 Manifest 导航/按钮（PC-5）。
 */
import { ref, computed, onMounted } from "vue";
import { apiBase, probeLiveness } from "../../api/platform";
import { isSignedIn } from "../../api/auth";
import { readBearer } from "../../api/auth-token";
import {
  BUTTON_SHELL_REPROBE,
  SHELL_MENU_LABEL_BY_ID,
  buttonAllowed,
  fetchUiPermissions,
  menuEntries,
  missingPermissionHint,
  type UiPermissionManifest,
} from "../../api/ui-permissions";

const base = ref(apiBase());
const platformLabel = ref("unknown");
const livenessText = ref("（未探测 / not probed）");
const signedIn = ref(false);
const manifest = ref<UiPermissionManifest | undefined>(undefined);
const manifestError = ref<string | null>(null);

const reprobeAllowed = computed(() =>
  buttonAllowed(manifest.value, BUTTON_SHELL_REPROBE, true)
);

const manifestStatus = computed(() => {
  if (manifestError.value) return `加载失败 / failed: ${manifestError.value}`;
  if (!manifest.value) return "未加载 / not loaded";
  return `client=${manifest.value.client} version=${manifest.value.version} entries=${manifest.value.entries.length}`;
});

type NavItem = { id: string; href: string; label: string; allowed: boolean; hint: string };

const navItems = computed<NavItem[]>(() => {
  return menuEntries(manifest.value).map((e) => ({
    id: e.id,
    href: (e.href || "").trim(),
    label: SHELL_MENU_LABEL_BY_ID[e.id] || e.id,
    allowed: Boolean(e.allowed),
    hint: missingPermissionHint(e),
  }));
});

function detectPlatform(): string {
  try {
    const info = uni.getSystemInfoSync() as UniApp.GetSystemInfoResult & {
      uniPlatform?: string;
    };
    const uniPlat = info.uniPlatform || "";
    const mp = typeof info.platform === "string" ? info.platform : "";
    return uniPlat || mp || "unknown";
  } catch {
    return "unknown";
  }
}

async function refreshProbe() {
  livenessText.value = "探测中… / probing…";
  try {
    const result = await probeLiveness();
    if (typeof result === "string") {
      livenessText.value = result.slice(0, 120);
    } else {
      livenessText.value = result.status || JSON.stringify(result).slice(0, 120);
    }
  } catch (e) {
    livenessText.value = e instanceof Error ? e.message : String(e);
  }
}

function onReprobe() {
  if (!reprobeAllowed.value) return;
  void refreshProbe();
}

/**
 * Load Manifest; 401 without Bearer → soft fail (shell still usable).
 * 加载 Manifest；无 Bearer 时 401 软失败（壳仍可用）。
 */
async function refreshManifest() {
  manifestError.value = null;
  try {
    manifest.value = await fetchUiPermissions(readBearer());
  } catch (e) {
    manifest.value = undefined;
    const msg = e instanceof Error ? e.message : String(e);
    manifestError.value = msg.includes("401")
      ? "需登录 Bearer（#3 终端认证后）/ Bearer required (after #3 auth)"
      : msg.slice(0, 160);
  }
}

onMounted(() => {
  platformLabel.value = detectPlatform();
  signedIn.value = isSignedIn();
  void refreshProbe();
  void refreshManifest();
});
</script>

<style scoped>
.page {
  padding: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.card {
  background: #fff;
  border-radius: 12rpx;
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  border: 1px solid #e5e5e5;
}
.title {
  font-size: 32rpx;
  font-weight: 600;
}
.muted {
  color: #666;
  font-size: 24rpx;
}
.label {
  margin-top: 8rpx;
  color: #888;
  font-size: 22rpx;
}
.value {
  font-size: 28rpx;
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 24rpx;
}
.link {
  color: #2563eb;
  font-size: 28rpx;
}
.link.locked {
  color: #9ca3af;
}
.nav-row {
  padding: 8rpx 0;
}
.hint {
  color: #b45309;
  font-size: 22rpx;
}
button.locked {
  opacity: 0.5;
}
</style>
