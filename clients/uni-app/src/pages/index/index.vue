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
      <text class="label">存活探针 / Liveness</text>
      <text class="value">{{ livenessText }}</text>
      <button size="mini" type="primary" @click="refreshProbe">重新探测 / Re-probe</button>
    </view>

    <view class="card">
      <navigator url="/pages/login/login" class="link">登录占位 / Login placeholder →</navigator>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Shell home: show uni platform + API base + optional liveness.
 * 壳首页：展示 uni 平台、API 根与可选存活探针。
 */
import { ref, onMounted } from "vue";
import { apiBase, probeLiveness } from "../../api/platform";

const base = ref(apiBase());
const platformLabel = ref("unknown");
const livenessText = ref("（未探测 / not probed）");

function detectPlatform(): string {
  // uni.getSystemInfoSync().uniPlatform: e.g. web / mp-weixin
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

onMounted(() => {
  platformLabel.value = detectPlatform();
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
</style>
