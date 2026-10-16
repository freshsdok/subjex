<template>
  <view class="page">
    <view class="card">
      <text class="title">登录占位 / Login placeholder</text>
      <text class="body">
        微信小程序路径：wx.login → jsCode → 平台 IdP
        <text class="mono">POST /api/v1/auth/idp/{provider}/callback</text>
        （issuer <text class="mono">wechat:mini</text>，见
        <text class="mono">platform.idp.wechat.*</text>）。
      </text>
      <text class="body">
        Mini-program path: wx.login → jsCode → platform IdP callback
        (issuer <text class="mono">wechat:mini</text>). Protocol only on the
        server; this page does not call WeChat or store secrets.
      </text>
      <text class="warn">
        端用户令牌形态（Bearer / OP）尚未定稿；禁止复用操作员控制台 Redis 会话。
        End-user token shape TBD; do not reuse operator console Redis sessions.
      </text>
      <text class="muted" v-if="jsCodeHint">{{ jsCodeHint }}</text>
      <button size="mini" @click="tryWxLoginStub">演示：读取 jsCode 占位 / Demo stub</button>
      <navigator url="/pages/index/index" class="link">← 返回壳首页 / Back</navigator>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * Login placeholder — documents WeChat mini IdP; no real credentials.
 * 登录占位：说明微信小程序 IdP；不接真实密钥。
 */
import { ref } from "vue";

const jsCodeHint = ref("");

function tryWxLoginStub() {
  // #ifdef MP-WEIXIN
  uni.login({
    provider: "weixin",
    success: (res) => {
      // Do not POST to production IdP from this scaffold without product auth design.
      // 脚手架不向生产 IdP 提交；仅展示拿到的 code 长度。
      const code = res.code || "";
      jsCodeHint.value = code
        ? `拿到 jsCode（长度 ${code.length}，未上传）/ got jsCode (len ${code.length}, not sent)`
        : "uni.login 无 code / no code";
    },
    fail: (err) => {
      jsCodeHint.value = `uni.login 失败 / failed: ${JSON.stringify(err)}`;
    },
  });
  // #endif
  // #ifndef MP-WEIXIN
  jsCodeHint.value =
    "当前非微信小程序运行时；H5 请走开放平台扫码 / OIDC（wechat:open）。 / Not mp-weixin; use open-platform QR / OIDC on H5.";
  // #endif
}
</script>

<style scoped>
.page {
  padding: 24rpx;
}
.card {
  background: #fff;
  border-radius: 12rpx;
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  border: 1px solid #e5e5e5;
}
.title {
  font-size: 32rpx;
  font-weight: 600;
}
.body {
  font-size: 26rpx;
  color: #333;
}
.warn {
  font-size: 24rpx;
  color: #b45309;
  background: #fffbeb;
  padding: 16rpx;
  border-radius: 8rpx;
}
.muted {
  font-size: 24rpx;
  color: #666;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 22rpx;
}
.link {
  color: #2563eb;
  margin-top: 12rpx;
}
</style>
