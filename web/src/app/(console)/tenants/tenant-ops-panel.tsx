"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { downloadBlob, filenameFromContentDisposition } from "@/lib/download-blob";
import {
  buildEraseConfirmBody,
  buildQuotaOverrideBody,
  canStartHardErase,
  type EraseChallengeDocument,
  type EraseResultDocument,
  eraseIdConfirmationMatches,
  parseQuotaLimitInput,
  type TenantQuotaDocument,
  tenantZipFilename,
} from "@/lib/tenant-ops";

type PanelStep = "closed" | "open";
type EraseStep = "idle" | "typed" | "challenged";

/**
 * Tenant ops panel — quota usage, ZIP backup, and a multi-step hard-erase flow.
 * 租户运营面板：用量、备份下载，以及多步确认的永久删除。
 */
export function TenantOpsPanel({
  tenantId,
  tenantName,
  tenantState,
  phrases,
  canManage,
}: {
  tenantId: string;
  tenantName: string;
  tenantState: string;
  phrases: PhraseBook;
  canManage: boolean;
}) {
  const router = useRouter();
  const [step, setStep] = useState<PanelStep>("closed");
  const [quota, setQuota] = useState<TenantQuotaDocument | null>(null);
  const [dailyInput, setDailyInput] = useState("");
  const [storageInput, setStorageInput] = useState("");
  const [quotaBusy, setQuotaBusy] = useState(false);
  const [zipBusy, setZipBusy] = useState(false);
  const [eraseBusy, setEraseBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [typedId, setTypedId] = useState("");
  const [tokenInput, setTokenInput] = useState("");
  const [challenge, setChallenge] = useState<EraseChallengeDocument | null>(null);
  const [eraseStep, setEraseStep] = useState<EraseStep>("idle");

  async function openPanel() {
    setProblem(null);
    setNotice(null);
    setStep("open");
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/quota`).catch(
      () => null,
    );
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.tenantQuotaLoadFailed, { status: String(reply?.status ?? 0) }));
      return;
    }
    const body = (await reply.json()) as TenantQuotaDocument;
    setQuota(body);
    setDailyInput(
      body.overrideDailySubmitLimit != null ? String(body.overrideDailySubmitLimit) : "",
    );
    setStorageInput(
      body.overrideStorageRowLimit != null ? String(body.overrideStorageRowLimit) : "",
    );
  }

  async function saveQuota() {
    setProblem(null);
    setNotice(null);
    const daily = parseQuotaLimitInput(dailyInput);
    const storage = parseQuotaLimitInput(storageInput);
    if (!daily.ok || !storage.ok) {
      setProblem(phrases.tenantQuotaLimitInvalid);
      return;
    }
    setQuotaBusy(true);
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/quota`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(buildQuotaOverrideBody(daily.value, storage.value)),
    }).catch(() => null);
    setQuotaBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const reason = await readReason(reply);
      setProblem(
        fillPhrase(phrases.tenantQuotaSaveFailed, {
          status: String(reply?.status ?? 0),
          reason: reason ? ` ${reason}` : "",
        }),
      );
      return;
    }
    const body = (await reply.json()) as TenantQuotaDocument;
    setQuota(body);
    setNotice(phrases.tenantQuotaSaved);
  }

  async function downloadZip() {
    setProblem(null);
    setNotice(null);
    setZipBusy(true);
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/export`, {
      method: "POST",
      headers: { accept: "application/zip, application/json" },
    }).catch(() => null);
    setZipBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const reason = await readReason(reply);
      setProblem(
        fillPhrase(phrases.tenantZipFailed, {
          status: String(reply?.status ?? 0),
          reason: reason ? ` ${reason}` : "",
        }),
      );
      return;
    }
    const blob = await reply.blob();
    const fallback = tenantZipFilename(tenantId);
    downloadBlob(blob, filenameFromContentDisposition(reply.headers.get("content-disposition"), fallback));
    setNotice(phrases.tenantZipDone);
  }

  async function requestEraseChallenge() {
    setProblem(null);
    setNotice(null);
    if (!canStartHardErase(tenantId, tenantState)) {
      setProblem(phrases.tenantEraseNeedDisable);
      return;
    }
    if (!eraseIdConfirmationMatches(typedId, tenantId)) {
      setProblem(phrases.tenantEraseIdMismatch);
      return;
    }
    setEraseBusy(true);
    const reply = await fetch(
      `/api/platform/tenants/${encodeURIComponent(tenantId)}/erase/challenge`,
      { method: "POST", headers: { accept: "application/json" } },
    ).catch(() => null);
    setEraseBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const reason = await readReason(reply);
      setProblem(
        fillPhrase(phrases.tenantEraseChallengeFailed, {
          status: String(reply?.status ?? 0),
          reason: reason ? ` ${reason}` : "",
        }),
      );
      return;
    }
    const body = (await reply.json()) as EraseChallengeDocument;
    setChallenge(body);
    setTokenInput("");
    setEraseStep("challenged");
    setNotice(phrases.tenantEraseChallengeIssued);
  }

  async function confirmErase() {
    setProblem(null);
    setNotice(null);
    if (!challenge?.challengeId) {
      setProblem(phrases.tenantEraseNeedChallenge);
      return;
    }
    if (!tokenInput.trim()) {
      setProblem(phrases.tenantEraseTokenRequired);
      return;
    }
    setEraseBusy(true);
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/erase`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify(buildEraseConfirmBody(challenge.challengeId, tokenInput)),
    }).catch(() => null);
    setEraseBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const reason = await readReason(reply);
      setProblem(
        fillPhrase(phrases.tenantEraseFailed, {
          status: String(reply?.status ?? 0),
          reason: reason ? ` ${reason}` : "",
        }),
      );
      return;
    }
    const body = (await reply.json()) as EraseResultDocument;
    setChallenge(null);
    setTypedId("");
    setTokenInput("");
    setEraseStep("idle");
    setNotice(
      fillPhrase(phrases.tenantEraseDone, {
        rows: String(body.rowsDeleted ?? 0),
        objects: String(body.objectStorageDeletes ?? 0),
      }),
    );
    router.refresh();
  }

  if (step === "closed") {
    return (
      <button
        type="button"
        onClick={openPanel}
        className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
      >
        {phrases.tenantOpsOpenAction}
      </button>
    );
  }

  const erasable = canStartHardErase(tenantId, tenantState);

  return (
    <div className="mt-2 w-full max-w-lg rounded-md border border-border bg-background p-3 text-xs">
      <div className="flex items-start justify-between gap-2">
        <h3 className="font-medium">{fillPhrase(phrases.tenantOpsTitle, { name: tenantName || tenantId })}</h3>
        <button
          type="button"
          onClick={() => setStep("closed")}
          className="rounded-md border border-border px-2 py-0.5 hover:bg-surface"
        >
          {phrases.tenantOpsCloseAction}
        </button>
      </div>

      <section className="mt-3 space-y-2">
        <h4 className="font-medium">{phrases.tenantQuotaTitle}</h4>
        <p className="text-muted">{phrases.tenantQuotaHint}</p>
        {quota ? (
          <ul className="space-y-1">
            <li>
              {fillPhrase(phrases.tenantQuotaDailyUsage, {
                count: String(quota.dailySubmitCount ?? 0),
                limit: String(quota.effectiveDailySubmitLimit ?? 0),
              })}
            </li>
            <li>
              {fillPhrase(phrases.tenantQuotaStorageUsage, {
                count: String(quota.storageRowCount ?? 0),
                limit: String(quota.effectiveStorageRowLimit ?? 0),
              })}
            </li>
          </ul>
        ) : (
          <p className="text-muted">{phrases.tenantQuotaLoading}</p>
        )}
        {canManage && quota ? (
          <div className="flex flex-col gap-2">
            <label className="flex flex-col gap-1">
              {fillPhrase(phrases.tenantQuotaDailyOverrideLabel, {
                global: String(quota.globalDailySubmitLimit ?? 0),
              })}
              <input
                value={dailyInput}
                onChange={(event) => setDailyInput(event.target.value)}
                className="rounded-md border border-border bg-surface px-2 py-1 font-mono"
                autoComplete="off"
              />
            </label>
            <label className="flex flex-col gap-1">
              {fillPhrase(phrases.tenantQuotaStorageOverrideLabel, {
                global: String(quota.globalStorageRowLimit ?? 0),
              })}
              <input
                value={storageInput}
                onChange={(event) => setStorageInput(event.target.value)}
                className="rounded-md border border-border bg-surface px-2 py-1 font-mono"
                autoComplete="off"
              />
            </label>
            <button
              type="button"
              onClick={saveQuota}
              disabled={quotaBusy}
              className="self-start rounded-md bg-foreground px-3 py-1 text-background disabled:opacity-60"
            >
              {quotaBusy ? phrases.tenantQuotaSaving : phrases.tenantQuotaSaveAction}
            </button>
          </div>
        ) : null}
      </section>

      {canManage ? (
        <section className="mt-4 space-y-2">
          <h4 className="font-medium">{phrases.tenantZipTitle}</h4>
          <p className="text-muted">{phrases.tenantZipHint}</p>
          <button
            type="button"
            onClick={downloadZip}
            disabled={zipBusy}
            className="rounded-md border border-border px-3 py-1 hover:bg-surface disabled:opacity-60"
          >
            {zipBusy ? phrases.tenantZipWorking : phrases.tenantZipAction}
          </button>
        </section>
      ) : null}

      {canManage ? (
        <section className="mt-4 space-y-2">
          <h4 className="font-medium text-red-800">{phrases.tenantEraseTitle}</h4>
          <p className="text-muted">{phrases.tenantEraseHint}</p>
          {!erasable ? (
            <p>{phrases.tenantEraseNeedDisable}</p>
          ) : (
            <>
              <label className="flex flex-col gap-1">
                {fillPhrase(phrases.tenantEraseTypeIdLabel, { id: tenantId })}
                <input
                  value={typedId}
                  onChange={(event) => {
                    setTypedId(event.target.value);
                    if (eraseStep === "idle") setEraseStep("typed");
                  }}
                  className="rounded-md border border-border bg-surface px-2 py-1 font-mono"
                  autoComplete="off"
                />
              </label>
              {eraseStep !== "challenged" ? (
                <button
                  type="button"
                  onClick={requestEraseChallenge}
                  disabled={eraseBusy}
                  className="rounded-md border border-red-300 px-3 py-1 text-red-800 hover:bg-red-50 disabled:opacity-60"
                >
                  {eraseBusy ? phrases.tenantEraseChallengeWorking : phrases.tenantEraseChallengeAction}
                </button>
              ) : (
                <>
                  <p role="status">{phrases.tenantEraseTokenHint}</p>
                  {challenge?.token ? (
                    <p className="break-all rounded-md border border-border bg-surface px-2 py-1 font-mono">
                      {challenge.token}
                    </p>
                  ) : null}
                  {challenge?.expiresAt ? (
                    <p className="text-muted">
                      {fillPhrase(phrases.tenantEraseExpires, { at: formatExpiry(challenge.expiresAt) })}
                    </p>
                  ) : null}
                  <label className="flex flex-col gap-1">
                    {phrases.tenantEraseTokenLabel}
                    <input
                      value={tokenInput}
                      onChange={(event) => setTokenInput(event.target.value)}
                      className="rounded-md border border-border bg-surface px-2 py-1 font-mono"
                      autoComplete="off"
                    />
                  </label>
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={confirmErase}
                      disabled={eraseBusy}
                      className="rounded-md bg-red-800 px-3 py-1 text-white disabled:opacity-60"
                    >
                      {eraseBusy ? phrases.tenantEraseWorking : phrases.tenantEraseConfirmAction}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setChallenge(null);
                        setTokenInput("");
                        setEraseStep("typed");
                      }}
                      disabled={eraseBusy}
                      className="rounded-md border border-border px-3 py-1"
                    >
                      {phrases.cancelAction}
                    </button>
                  </div>
                </>
              )}
            </>
          )}
        </section>
      ) : null}

      {problem && (
        <p role="alert" className="mt-3 text-red-700">
          {problem}
        </p>
      )}
      {notice && (
        <p role="status" className="mt-3 text-green-700">
          {notice}
        </p>
      )}
    </div>
  );
}

function formatExpiry(iso: string): string {
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return iso;
  return new Date(parsed).toLocaleString();
}

async function readReason(reply: Response | null): Promise<string> {
  if (!reply) return "";
  try {
    const body = (await reply.json()) as { reason?: unknown };
    return typeof body.reason === "string" ? body.reason : "";
  } catch {
    return "";
  }
}
