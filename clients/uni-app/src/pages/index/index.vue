<template>
  <view class="page">
    <view class="card">
      <text class="title">欢迎</text>
      <text class="muted">请从下面进入资料、站内信、问卷和文件。</text>
    </view>

    <view class="card">
      <text class="label">运行平台 / Platform</text>
      <text class="value">{{ platformLabel }}</text>
      <text class="label">API 根 / API base</text>
      <text class="value mono">{{ base }}</text>
      <text class="label">登录状态 / Auth</text>
      <text class="value">{{ signedIn ? "已登录" : "未登录" }}</text>
      <text class="label">业务租户 / Tenant</text>
      <text class="value mono">{{ tenantLabel }}</text>
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
import { ensureSession, isSignedIn } from "../../api/auth";
import { readBearer } from "../../api/auth-token";
import { readTenantId } from "../../api/tenant";
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
const tenantLabel = ref("（未设置 / not set）");
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

const FALLBACK_NAV: NavItem[] = [
  { id: "nav.home", href: "/pages/index/index", label: SHELL_MENU_LABEL_BY_ID["nav.home"], allowed: true, hint: "" },
  { id: "nav.login", href: "/pages/login/login", label: SHELL_MENU_LABEL_BY_ID["nav.login"], allowed: true, hint: "" },
  { id: "nav.profile", href: "/pages/profile/profile", label: SHELL_MENU_LABEL_BY_ID["nav.profile"], allowed: true, hint: "" },
  { id: "nav.inbox", href: "/pages/inbox/inbox", label: SHELL_MENU_LABEL_BY_ID["nav.inbox"], allowed: true, hint: "" },
  {
    id: "nav.questionnaires",
    href: "/pages/questionnaire/list",
    label: SHELL_MENU_LABEL_BY_ID["nav.questionnaires"],
    allowed: true,
    hint: "",
  },
  { id: "nav.files", href: "/pages/files/files", label: SHELL_MENU_LABEL_BY_ID["nav.files"], allowed: true, hint: "" },
];

const navItems = computed<NavItem[]>(() => {
  const entries = menuEntries(manifest.value);
  // End-user tokens are 403 on the operator UI-permission API; keep the shell usable.
  // 终端令牌访问操作员 UI 权限接口会 403；用本地导航保证壳仍可用。
  if (entries.length === 0) return FALLBACK_NAV;
  return entries.map((e) => ({
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
 * Load Manifest; 401/403 soft-fail and fall back to local nav.
 * 加载 Manifest；401/403 软失败并回退到本地导航。
 * GET /api/v1/ui-permissions is operator-gated (WP2-5); end-user tokens receive 403.
 * 该接口仍要求操作员主体；终端令牌会被拒绝，这是预期而非登录失败。
 */
async function refreshManifest() {
  manifestError.value = null;
  try {
    manifest.value = await fetchUiPermissions(readBearer());
  } catch (e) {
    manifest.value = undefined;
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.includes("403") || msg.includes("operator-authentication-required")) {
      manifestError.value =
        "终端令牌不能读取操作员 UI 权限清单，已使用本地导航 / end-user tokens cannot read the operator UI-permission manifest; using local nav";
    } else if (msg.includes("401")) {
      manifestError.value = "需先登录终端令牌 / sign in with an end-user token first";
    } else {
      manifestError.value = msg.slice(0, 160);
    }
  }
}

onMounted(() => {
  platformLabel.value = detectPlatform();
  tenantLabel.value = readTenantId() || "（未设置 / not set）";
  void (async () => {
    await ensureSession();
    signedIn.value = isSignedIn();
    void refreshManifest();
  })();
  void refreshProbe();
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
