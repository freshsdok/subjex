<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的支付</text>
      <text class="muted">登录后可以查看您在本组织的支付单，并取消尚未支付的单据。</text>
      <navigator :url="loginHref" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的支付</text>
      <text class="muted">
        这里只列出您在本组织的支付单。待支付的单据可以取消；未支付单据会在创建后 30 分钟过期。
        本页不能下单或付款。成功入账由支付通道回调完成。
      </text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <button size="mini" :disabled="busy || loading" @click="refresh">刷新</button>
    </view>

    <view class="card" v-if="signedIn && !loading">
      <text class="title">我的单据</text>
      <text class="muted" v-if="!items.length">还没有支付单。</text>
      <view v-for="item in items" :key="item.paymentId" class="item">
        <text class="entry-title">{{ paymentStatusLabel(item.status) }}</text>
        <text class="muted">金额 {{ paymentAmountLabel(item.amount) }}</text>
        <text class="muted" v-if="hasRefund(item)">已退 {{ paymentAmountLabel(item.refundedAmount) }}</text>
        <text class="muted" v-if="item.reference">业务编号 {{ item.reference }}</text>
        <text class="muted" v-if="item.createdAt">创建时间 {{ formatPaymentTime(item.createdAt) }}</text>
        <text class="muted" v-if="item.status === 'PENDING' && item.expiresAt">
          请在 {{ formatPaymentTime(item.expiresAt) }} 前完成支付，过期后单据会失效。
        </text>
        <text class="muted" v-else-if="item.status === 'EXPIRED'">这份单据已过期，不能再取消。</text>
        <text class="muted" v-else-if="item.status === 'CANCELLED' && item.cancelledAt">
          取消时间 {{ formatPaymentTime(item.cancelledAt) }}
        </text>
        <text class="muted" v-else-if="item.succeededAt">入账时间 {{ formatPaymentTime(item.succeededAt) }}</text>
        <text class="muted" v-if="item.paymentId">单据编号 {{ shortPaymentId(item.paymentId) }}</text>
        <button
          v-if="canCancelPayment(item)"
          size="mini"
          :disabled="busy"
          @click="onCancel(item)"
        >
          {{ busy ? "请稍候…" : "取消这份单据" }}
        </button>
      </view>
    </view>

    <navigator open-type="switchTab" url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * End-user own payments: list, show status, cancel PENDING. No create and no payer charge.
 * 终端本人支付单：列表、状态、取消待支付。页面不能下单或付款。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import {
  canCancelPayment,
  cancelOwnPayment,
  formatPaymentTime,
  listOwnPayments,
  paymentAmountLabel,
  paymentMessage,
  paymentStatusLabel,
  shortPaymentId,
  type EndUserPayment,
} from "../../api/payments";

const signedIn = ref(isSignedIn());
const loginHref = loginPageUrl("/pages/payment/payment");
const items = ref<EndUserPayment[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");

function hasRefund(item: EndUserPayment): boolean {
  return (item.refundedAmount?.amountMinor ?? 0) > 0;
}

async function refresh() {
  signedIn.value = isSignedIn();
  error.value = "";
  if (!signedIn.value) {
    items.value = [];
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    items.value = await listOwnPayments();
  } catch (e) {
    error.value = paymentMessage(e);
    items.value = [];
  } finally {
    loading.value = false;
  }
}

async function onCancel(item: EndUserPayment) {
  if (!item.paymentId || busy.value || !canCancelPayment(item)) return;
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: "取消这份单据",
      content: "取消后不能再支付这份单据。确定要取消吗？",
      confirmText: "取消单据",
      cancelText: "再想想",
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false),
    });
  });
  if (!confirmed) return;
  busy.value = true;
  error.value = "";
  try {
    await cancelOwnPayment(item.paymentId);
    uni.showToast({ title: "已取消", icon: "none" });
    await refresh();
  } catch (e) {
    error.value = paymentMessage(e);
    uni.showToast({ title: error.value, icon: "none", duration: 2500 });
    await refresh();
  } finally {
    busy.value = false;
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
