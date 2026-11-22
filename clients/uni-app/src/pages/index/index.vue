<template>
  <view class="page">
    <view class="card">
      <text class="title">欢迎</text>
      <text class="muted">在这里查看您的资料、站内信、问卷、文件、同意记录和导出申请。</text>
      <text class="value">{{ signedIn ? "您已登录。" : "您尚未登录。" }}</text>
      <text class="muted">{{ tenantText }}</text>
    </view>

    <view class="card">
      <text class="label">功能</text>
      <view v-for="item in navItems" :key="item.id" class="nav-row">
        <navigator v-if="item.allowed && item.href" :url="item.href" class="link">
          {{ item.label }}
        </navigator>
        <text v-else class="link locked">
          {{ item.label }}（暂不可用）
        </text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Shell home: human usage hub to every already-built end-user page.
 * 壳首页：面向使用的入口，通向已有的终端功能页。
 */
import { ref, computed, onMounted } from "vue";
import { ensureSession, isSignedIn } from "../../api/auth";
import { readBearer } from "../../api/auth-token";
import { readTenantId } from "../../api/tenant";
import {
  SHELL_MENU_LABEL_BY_ID,
  fetchUiPermissions,
  menuEntries,
  type UiPermissionManifest,
} from "../../api/ui-permissions";

const signedIn = ref(false);
const tenantId = ref("");
const manifest = ref<UiPermissionManifest | undefined>(undefined);

const tenantText = computed(() => {
  const id = tenantId.value.trim();
  if (id) return `当前组织：${id}`;
  return "还没有选择组织。登录时请填写您所在的组织编号。";
});

type NavItem = { id: string; href: string; label: string; allowed: boolean };

const FALLBACK_NAV: NavItem[] = [
  { id: "nav.home", href: "/pages/index/index", label: SHELL_MENU_LABEL_BY_ID["nav.home"], allowed: true },
  { id: "nav.login", href: "/pages/login/login", label: SHELL_MENU_LABEL_BY_ID["nav.login"], allowed: true },
  { id: "nav.profile", href: "/pages/profile/profile", label: SHELL_MENU_LABEL_BY_ID["nav.profile"], allowed: true },
  { id: "nav.inbox", href: "/pages/inbox/inbox", label: SHELL_MENU_LABEL_BY_ID["nav.inbox"], allowed: true },
  {
    id: "nav.questionnaires",
    href: "/pages/questionnaire/list",
    label: SHELL_MENU_LABEL_BY_ID["nav.questionnaires"],
    allowed: true,
  },
  { id: "nav.files", href: "/pages/files/files", label: SHELL_MENU_LABEL_BY_ID["nav.files"], allowed: true },
  { id: "nav.consents", href: "/pages/consent/consent", label: SHELL_MENU_LABEL_BY_ID["nav.consents"], allowed: true },
  { id: "nav.exports", href: "/pages/export/export", label: SHELL_MENU_LABEL_BY_ID["nav.exports"], allowed: true },
];

/**
 * Prefer Manifest menus when they load, but always keep a link to every built shell page.
 * 清单能加载时用清单菜单，同时补上清单里还没有的已有壳页。
 */
const navItems = computed<NavItem[]>(() => {
  const merged = mergeNav(manifest.value);
  return merged.filter((item) => {
    if (item.id === "nav.home") return false;
    if (item.id === "nav.login") return !signedIn.value;
    return true;
  });
});

function mergeNav(loaded: UiPermissionManifest | undefined): NavItem[] {
  const entries = menuEntries(loaded);
  const items: NavItem[] = [];
  const seen = new Set<string>();
  for (const entry of entries) {
    const href = (entry.href || "").trim();
    if (!href || seen.has(entry.id) || seen.has(href)) continue;
    items.push({
      id: entry.id,
      href,
      label: SHELL_MENU_LABEL_BY_ID[entry.id] || entry.id,
      allowed: Boolean(entry.allowed),
    });
    seen.add(entry.id);
    seen.add(href);
  }
  for (const fallback of FALLBACK_NAV) {
    if (seen.has(fallback.id) || seen.has(fallback.href)) continue;
    items.push(fallback);
    seen.add(fallback.id);
    seen.add(fallback.href);
  }
  return items.length > 0 ? items : FALLBACK_NAV;
}

async function refreshManifest() {
  try {
    manifest.value = await fetchUiPermissions(readBearer());
  } catch {
    // End-user tokens are 403 on the operator UI-permission API; local nav stays usable.
    // 终端令牌访问操作员 UI 权限接口会 403；本地导航仍可用。
    manifest.value = undefined;
  }
}

onMounted(() => {
  tenantId.value = readTenantId() || "";
  void (async () => {
    await ensureSession();
    signedIn.value = isSignedIn();
    void refreshManifest();
  })();
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
</style>
