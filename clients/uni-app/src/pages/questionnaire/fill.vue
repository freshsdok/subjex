<template>
  <view class="page">
    <view class="card">
      <text class="title">填写问卷</text>
      <text class="muted" v-if="mode === 'token'">您正在通过填写码打开这份问卷，无需登录。</text>
      <text class="muted" v-else-if="mode === 'auth'">请根据题目如实填写，提交后通常不能再改。</text>
      <text class="muted" v-else>正在打开问卷…</text>
      <text class="muted" v-if="busy && !definition">正在加载…</text>
      <text class="warn" v-if="error">{{ error }}</text>
    </view>

    <view class="card" v-if="definition">
      <text class="entry-title">{{ definition.title }}</text>

      <view v-for="item in sortedItems" :key="item.itemId" class="item">
        <text class="prompt">
          {{ item.prompt }}
          <text v-if="item.required" class="req">*</text>
        </text>

        <radio-group
          v-if="item.questionType === 'SINGLE_CHOICE'"
          @change="(e: any) => setAnswer(item.itemId!, e.detail.value)"
        >
          <label v-for="opt in optionsOf(item)" :key="opt" class="opt">
            <radio :value="opt" :checked="answers[item.itemId!] === opt" />
            <text>{{ opt }}</text>
          </label>
        </radio-group>

        <checkbox-group
          v-else-if="item.questionType === 'MULTI_CHOICE'"
          @change="(e: any) => setAnswer(item.itemId!, e.detail.value)"
        >
          <label v-for="opt in optionsOf(item)" :key="opt" class="opt">
            <checkbox
              :value="opt"
              :checked="Array.isArray(answers[item.itemId!]) && (answers[item.itemId!] as string[]).includes(opt)"
            />
            <text>{{ opt }}</text>
          </label>
        </checkbox-group>

        <picker
          v-else-if="item.questionType === 'RATING'"
          :range="ratingRange(item)"
          @change="(e: any) => setAnswer(item.itemId!, ratingRange(item)[Number(e.detail.value)])"
        >
          <view class="picker">
            {{ answers[item.itemId!] != null ? String(answers[item.itemId!]) : "请选择评分" }}
          </view>
        </picker>

        <textarea
          v-else-if="item.questionType === 'TEXT_LONG'"
          class="area"
          :value="String(answers[item.itemId!] ?? '')"
          @input="(e: any) => setAnswer(item.itemId!, e.detail.value)"
        />

        <input
          v-else
          class="input"
          :value="String(answers[item.itemId!] ?? '')"
          @input="(e: any) => setAnswer(item.itemId!, e.detail.value)"
        />
      </view>

      <button type="primary" :disabled="busy || submitted" @click="onSubmit">
        {{ submitted ? "已提交" : busy ? "正在提交…" : "提交" }}
      </button>
      <text class="ok" v-if="submitResult">{{ submitResult }}</text>
      <button class="share-btn" :disabled="!canShare" @click="onCopyShareLink">复制分享链接</button>
      <text class="muted">只有你点分享或复制链接时，才会记下一次分享；带着「来自分享」或活动/来源参数打开时，才会记下一次打开。普通进入本页不会被悄悄记录。</text>
    </view>

    <navigator open-type="switchTab" url="/pages/questionnaire/list" class="link bottom">返回问卷列表</navigator>
  </view>
</template>

<script setup lang="ts">
/**
 * Fill a questionnaire by collect token (anon) or authenticated id (WP7-3).
 * 凭填写码（匿名）或登录后的问卷 id 填写。
 */
import { ref, computed } from "vue";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import {
  encodeAnswerValue,
  getQuestionnaire,
  parseOptions,
  publicGetByToken,
  publicSubmit,
  submitQuestionnaire,
  type QuestionnaireDefinition,
  type QuestionnaireItem,
} from "../../api/questionnaire";
import { messageForHttpError, toastHttpError } from "../../api/platform";
import { applyTenantFromQuery, readTenantId } from "../../api/tenant";
import {
  RESOURCE_TYPE_QUESTIONNAIRE,
  attributionFromQuery,
  buildShareQueryString,
  isSharedOpenQuery,
  tryRecordResourceEvent,
  type ResourceEventAttribution,
} from "../../api/resource-events";

const mode = ref<"auth" | "token" | "">("");
const tokenId = ref("");
const questionnaireId = ref("");
const definition = ref<QuestionnaireDefinition | null>(null);
const answers = ref<Record<string, unknown>>({});
const error = ref("");
const busy = ref(false);
const submitted = ref(false);
const submitResult = ref("");

const sortedItems = computed(() => {
  const items = definition.value?.items ?? [];
  return [...items].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
});

function optionsOf(item: QuestionnaireItem): string[] {
  return parseOptions(item.optionsJson);
}

function ratingRange(item: QuestionnaireItem): number[] {
  const opts = optionsOf(item);
  let max = 5;
  if (opts.length === 1 && /^\d+$/.test(opts[0])) {
    max = Math.min(10, Math.max(1, Number(opts[0])));
  }
  return Array.from({ length: max }, (_, i) => i + 1);
}

function setAnswer(itemId: string, value: unknown) {
  answers.value = { ...answers.value, [itemId]: value };
}

async function load() {
  busy.value = true;
  error.value = "";
  submitResult.value = "";
  submitted.value = false;
  answers.value = {};
  try {
    if (mode.value === "auth") {
      definition.value = await getQuestionnaire(questionnaireId.value);
    } else {
      definition.value = await publicGetByToken(tokenId.value);
    }
  } catch (e) {
    definition.value = null;
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}

async function onSubmit() {
  if (!definition.value || submitted.value) return;
  const items = sortedItems.value;
  const payload = [];
  for (const item of items) {
    const id = item.itemId;
    if (!id) continue;
    const raw = answers.value[id];
    if (raw === undefined || raw === null || raw === "") {
      if (item.required) {
        error.value = `请填写：${item.prompt || "必填题"}`;
        toastHttpError(new Error(error.value));
        return;
      }
      continue;
    }
    payload.push({ itemId: id, valueJson: encodeAnswerValue(raw) });
  }
  busy.value = true;
  error.value = "";
  try {
    if (mode.value === "auth") {
      await submitQuestionnaire(questionnaireId.value, payload);
    } else {
      await publicSubmit(tokenId.value, payload);
    }
    submitted.value = true;
    submitResult.value = "提交成功，感谢您的参与。";
    uni.showToast({ title: "提交成功", icon: "none" });
  } catch (e) {
    error.value = messageForHttpError(e);
    toastHttpError(e);
  } finally {
    busy.value = false;
  }
}


const attribution = ref<ResourceEventAttribution>({});
const canShare = computed(() => Boolean(currentResourceId() || tokenId.value));

function currentResourceId(): string {
  return (definition.value?.questionnaireId || questionnaireId.value || "").trim();
}

function fillShareQuery(): string {
  const base: Record<string, string | undefined> = {};
  if (mode.value === "token" && tokenId.value) {
    base.token = tokenId.value;
  } else if (questionnaireId.value) {
    base.id = questionnaireId.value;
  }
  return buildShareQueryString(base, attribution.value, readTenantId() || undefined);
}

function fillSharePath(): string {
  return `/pages/questionnaire/fill?${fillShareQuery()}`;
}

function shareHref(): string {
  const path = fillSharePath();
  try {
    if (typeof window !== "undefined" && window.location && window.location.href) {
      const url = new URL(window.location.href);
      if (url.hash) {
        url.hash = `#${path}`;
        return url.toString();
      }
    }
  } catch {
    // Mini-program or restricted runtime — 小程序或受限运行时。
  }
  return path;
}

function record(eventType: "share" | "open") {
  const resourceId = currentResourceId();
  if (!resourceId) return;
  tryRecordResourceEvent({
    resourceType: RESOURCE_TYPE_QUESTIONNAIRE,
    resourceId,
    eventType,
    tenantId: readTenantId() || undefined,
    ...attribution.value,
  });
}

function onCopyShareLink() {
  const href = shareHref();
  uni.setClipboardData({
    data: href,
    success: () => {
      record("share");
      uni.showToast({ title: "链接已复制", icon: "none" });
    },
  });
}

onShareAppMessage(() => {
  record("share");
  return {
    title: definition.value?.title || "填写问卷",
    path: fillSharePath(),
  };
});

onLoad((query) => {
  applyTenantFromQuery(query ?? {});
  attribution.value = attributionFromQuery(query ?? {});
  const id = typeof query?.id === "string" ? query.id : "";
  const t = typeof query?.token === "string" ? query.token : "";
  const shouldRecordOpen = isSharedOpenQuery(query ?? {});
  if (id) {
    mode.value = "auth";
    questionnaireId.value = decodeURIComponent(id);
    void load().then(() => {
      if (shouldRecordOpen) record("open");
    });
  } else if (t) {
    mode.value = "token";
    tokenId.value = decodeURIComponent(t);
    void load().then(() => {
      if (shouldRecordOpen) record("open");
    });
  } else {
    error.value = "缺少问卷信息，请从问卷列表进入";
  }
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
.prompt { font-size: 28rpx; }
.req { color: #b91c1c; }
.muted { font-size: 24rpx; color: #666; }
.warn { color: #b45309; font-size: 24rpx; }
.ok { color: #15803d; font-size: 24rpx; }
.input { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.area { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; min-height: 120rpx; }
.item { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 0; border-top: 1px solid #eee; }
.opt { display: flex; align-items: center; gap: 8rpx; margin: 4rpx 0; font-size: 26rpx; }
.picker { border: 1px solid #ddd; border-radius: 8rpx; padding: 12rpx; font-size: 26rpx; }
.link { color: #2563eb; font-size: 28rpx; }
.bottom { margin: 12rpx 24rpx 40rpx; }
.share-btn { margin-top: 12rpx; }

</style>
