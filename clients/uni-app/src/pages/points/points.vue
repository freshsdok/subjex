<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的积分</text>
      <text class="muted">登录后可以查看您在本组织的积分余额与调整历史。</text>
      <navigator :url="loginHref" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的积分</text>
      <text class="muted">
        这里只显示您在本组织的余额与历史。积分由组织操作员发放或扣减，不能在此自行调整，也不会在支付成功后自动增加。
      </text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <button size="mini" :disabled="loading" @click="refresh">刷新</button>
      <text class="title" v-if="!loading && !error">当前余额 {{ account.balance ?? 0 }}</text>
    </view>

    <view class="card" v-if="signedIn && !loading && !error">
      <text class="title">调整历史</text>
      <text class="muted" v-if="!entries.length">还没有调整记录。</text>
      <view v-for="item in entries" :key="item.entryId" class="item">
        <text class="entry-title">{{ directionLabel(item) }} {{ item.delta }} → {{ item.balanceAfter }}</text>
        <text class="muted" v-if="item.reason">原因 {{ item.reason }}</text>
        <text class="muted" v-if="item.memo">备注 {{ item.memo }}</text>
        <text class="muted" v-if="item.referenceType && item.referenceId">
          业务引用 {{ item.referenceType }} / {{ item.referenceId }}
        </text>
        <text class="muted" v-if="item.createdAt">时间 {{ formatTime(item.createdAt) }}</text>
      </view>
    </view>

    <navigator url="/pages/profile/profile" class="link bottom">返回我的资料</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * Own points balance and history in the current tenant. No self-adjust.
 * 本组织本人积分余额与历史。不能自行调整。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import { getOwnPoints, type PointsAccount, type PointsLedgerEntry } from "../../api/points";
import { PlatformHttpError } from "../../api/platform";

const signedIn = ref(false);
const loading = ref(false);
const error = ref("");
const account = ref<PointsAccount>({});
const loginHref = loginPageUrl("/pages/points/points");
const entries = computed(() => account.value.entries ?? []);

function directionLabel(item: PointsLedgerEntry): string {
  return item.direction === "DEDUCT" ? "扣减" : "发放";
}

function formatTime(value?: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

function pointsMessage(err: unknown): string {
  if (err instanceof PlatformHttpError) {
    return err.message || "未能加载积分台账。";
  }
  return "未能加载积分台账。";
}

async function refresh() {
  signedIn.value = isSignedIn();
  if (!signedIn.value) {
    account.value = {};
    error.value = "";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    account.value = await getOwnPoints();
  } catch (err) {
    error.value = pointsMessage(err);
    account.value = {};
  } finally {
    loading.value = false;
  }
}

onShow(() => {
  signedIn.value = isSignedIn();
  void refresh();
});
</script>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 20rpx; }
.card {
  background: #fff; border-radius: 12rpx; padding: 24rpx;
  display: flex; flex-direction: column; gap: 12rpx; border: 1px solid #e5e5e5;
}
.title { font-size: 32rpx; font-weight: 600; }
.entry-title { font-size: 28rpx; font-weight: 600; }
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.item { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 0; border-top: 1px solid #eee; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
