<template>
  <view class="page">
    <view class="card" v-if="!signedIn">
      <text class="title">我的支付</text>
      <text class="muted">登录后可以在本组织创建支付单、发起扣款，或取消尚未支付的单据。</text>
      <navigator :url="loginHref" class="link">去登录</navigator>
    </view>

    <view class="card" v-else>
      <text class="title">我的支付</text>
      <text class="muted">
        这里只列出您在本组织的支付单。您可以创建一笔通用支付（金额、币种、业务类型与编号），再对未支付单据发起扣款。
        发起扣款不会立刻记为成功，成功入账仍由支付通道回调完成。未支付单据会在创建后 30 分钟过期。
      </text>
      <text class="muted" v-if="loading">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
      <button size="mini" :disabled="busy || loading" @click="refresh">刷新</button>
    </view>

    <view class="card" v-if="signedIn">
      <text class="title">创建支付单</text>
      <text class="muted">填写金额、币种和业务引用即可。不是商品目录，也不是某一种行业结账。</text>
      <view class="field">
        <text class="label">金额</text>
        <input class="input" type="digit" v-model="draftAmount" placeholder="例如 1.99" />
      </view>
      <view class="field">
        <text class="label">币种</text>
        <input class="input" v-model="draftCurrency" placeholder="CNY" maxlength="3" />
      </view>
      <view class="field">
        <text class="label">业务类型</text>
        <input class="input" v-model="draftType" placeholder="例如 order" />
      </view>
      <view class="field">
        <text class="label">业务编号</text>
        <input class="input" v-model="draftId" placeholder="例如 ORD-1001" />
      </view>
      <view class="field">
        <text class="label">备注（可选）</text>
        <input class="input" v-model="draftMemo" placeholder="展示给自己看的说明" />
      </view>
      <button size="mini" :disabled="busy || loading" @click="onCreate">
        {{ busy ? "请稍候…" : "创建并待支付" }}
      </button>
    </view>

    <view class="card" v-if="signedIn && !loading">
      <text class="title">我的单据</text>
      <text class="muted" v-if="!items.length">还没有支付单。</text>
      <view v-for="item in items" :key="item.paymentId" class="item">
        <text class="entry-title">{{ paymentStatusLabel(item.status) }}</text>
        <text class="muted">金额 {{ paymentAmountLabel(item.amount) }}</text>
        <text class="muted" v-if="hasRefund(item)">已退 {{ paymentAmountLabel(item.refundedAmount) }}</text>
        <text class="muted" v-if="item.referenceType && item.referenceId">
          业务引用 {{ item.referenceType }} / {{ item.referenceId }}
        </text>
        <text class="muted" v-else-if="item.reference">业务编号 {{ item.reference }}</text>
        <text class="muted" v-if="item.memo">备注 {{ item.memo }}</text>
        <text class="muted" v-if="item.createdAt">创建时间 {{ formatPaymentTime(item.createdAt) }}</text>
        <text class="muted" v-if="item.status === 'PENDING' && item.expiresAt">
          请在 {{ formatPaymentTime(item.expiresAt) }} 前完成支付，过期后单据会失效。
        </text>
        <text class="muted" v-else-if="item.status === 'EXPIRED'">这份单据已过期，不能再取消或扣款。</text>
        <text class="muted" v-else-if="item.status === 'CANCELLED' && item.cancelledAt">
          取消时间 {{ formatPaymentTime(item.cancelledAt) }}
        </text>
        <text class="muted" v-else-if="item.succeededAt">入账时间 {{ formatPaymentTime(item.succeededAt) }}</text>
        <text class="muted" v-if="item.paymentId">单据编号 {{ shortPaymentId(item.paymentId) }}</text>
        <text class="muted" v-if="chargeHints[item.paymentId]">{{ chargeHints[item.paymentId] }}</text>
        <view class="actions">
          <button
            v-if="canStartCharge(item)"
            size="mini"
            :disabled="busy"
            @click="onCharge(item)"
          >
            {{ busy ? "请稍候…" : "发起扣款" }}
          </button>
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
    </view>

    <navigator open-type="switchTab" url="/pages/index/index" class="link bottom">返回首页</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * End-user own payments: create a generic payment, start charge, list, cancel PENDING.
 * 终端本人支付单：创建通用支付、发起扣款、列表、取消待支付。成功入账仍走通道回调。
 */
import { reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { isSignedIn } from "../../api/auth";
import { loginPageUrl } from "../../api/shell-path";
import {
  canCancelPayment,
  canStartCharge,
  cancelOwnPayment,
  createOwnPayment,
  formatPaymentTime,
  listOwnPayments,
  majorToMinor,
  newIdempotencyKey,
  paymentAmountLabel,
  paymentMessage,
  paymentStatusLabel,
  shortPaymentId,
  startChargeOwnPayment,
  type EndUserPayment,
} from "../../api/payments";

const signedIn = ref(isSignedIn());
const loginHref = loginPageUrl("/pages/payment/payment");
const items = ref<EndUserPayment[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const draftAmount = ref("");
const draftCurrency = ref("CNY");
const draftType = ref("");
const draftId = ref("");
const draftMemo = ref("");
const chargeHints = reactive<Record<string, string>>({});

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

async function onCreate() {
  if (busy.value) return;
  const currency = (draftCurrency.value || "CNY").trim().toUpperCase();
  const amountMinor = majorToMinor(draftAmount.value, currency);
  const type = draftType.value.trim();
  const id = draftId.value.trim();
  if (amountMinor == null || amountMinor < 1) {
    error.value = "请填写有效的金额";
    return;
  }
  if (!type || !id) {
    error.value = "请填写业务类型和业务编号";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await createOwnPayment({
      amountMinor,
      currency,
      referenceType: type,
      referenceId: id,
      memo: draftMemo.value.trim() || undefined,
      idempotencyKey: newIdempotencyKey(),
    });
    draftAmount.value = "";
    draftMemo.value = "";
    uni.showToast({ title: "已创建，待支付", icon: "none" });
    await refresh();
  } catch (e) {
    error.value = paymentMessage(e);
    uni.showToast({ title: error.value, icon: "none", duration: 2500 });
  } finally {
    busy.value = false;
  }
}

async function onCharge(item: EndUserPayment) {
  if (!item.paymentId || busy.value || !canStartCharge(item)) return;
  busy.value = true;
  error.value = "";
  try {
    const session = await startChargeOwnPayment(item.paymentId);
    const params = session.charge?.params || {};
    const requestPayment = params.requestPayment;
    if (requestPayment && typeof requestPayment === "object") {
      // Pass through a provider-supplied checkout payload only; do not invent official SDK fields.
      // 仅透传适配器提供的结账载荷，不编造官方 SDK 字段。
      await new Promise<void>((resolve, reject) => {
        const payload = {
          ...(requestPayment as Record<string, unknown>),
          success: () => resolve(),
          fail: (err: { errMsg?: string }) => reject(new Error(err?.errMsg || "发起扣款未完成")),
        };
        // Generic pass-through only; typings vary by uni-app runtime.
        // 仅通用透传；类型随运行时而异。
        (uni as { requestPayment: (opts: typeof payload) => void }).requestPayment(payload);
      });
    }
    const hint =
      params.mode === "TEST"
        ? "已向测试通道发起扣款。单据仍是待支付，成功入账需等待回调。"
        : "已向支付通道发起扣款。单据仍是待支付，成功入账需等待回调。";
    chargeHints[item.paymentId] = hint;
    uni.showToast({ title: "已发起扣款", icon: "none" });
    await refresh();
  } catch (e) {
    error.value = paymentMessage(e);
    uni.showToast({ title: error.value, icon: "none", duration: 2500 });
    await refresh();
  } finally {
    busy.value = false;
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
.field { display: flex; flex-direction: column; gap: 6rpx; }
.label { font-size: 24rpx; color: #333; }
.input {
  border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx 16rpx; font-size: 26rpx; background: #fafafa;
}
.actions { display: flex; flex-direction: row; gap: 12rpx; flex-wrap: wrap; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
</style>
