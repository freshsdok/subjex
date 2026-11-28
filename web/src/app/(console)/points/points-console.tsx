"use client";

import { useState, type FormEvent } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type LedgerEntry = {
  entryId?: string;
  delta?: number;
  balanceAfter?: number;
  direction?: string;
  reason?: string;
  memo?: string | null;
  referenceType?: string | null;
  referenceId?: string | null;
  actorSubjectId?: string | null;
  createdAt?: string;
};

type AccountDocument = {
  tenantId?: string;
  subjectId?: string;
  balance?: number;
  entries?: LedgerEntry[];
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  canWrite: boolean;
};

/**
 * Thin operator points console — look up a subject, view history, grant or deduct.
 * 薄操作员积分台：查阅主体、查看历史、发放或扣减。
 */
export function PointsConsole({ phrases, initialTenantId, canWrite }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [subjectId, setSubjectId] = useState("");
  const [account, setAccount] = useState<AccountDocument | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [errorReason, setErrorReason] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [direction, setDirection] = useState("GRANT");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [memo, setMemo] = useState("");
  const [referenceType, setReferenceType] = useState("");
  const [referenceId, setReferenceId] = useState("");

  const tenantQ = () => `tenantId=${encodeURIComponent(tenantId.trim())}`;

  async function loadAccount() {
    const tid = tenantId.trim();
    const sid = subjectId.trim();
    if (!tid || !sid) {
      setAccount(null);
      setStatus("idle");
      return;
    }
    setStatus("loading");
    setErrorStatus(0);
    setErrorReason(null);
    const reply = await fetch(
      `/api/platform/points?${tenantQ()}&subjectId=${encodeURIComponent(sid)}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (!reply?.ok) {
      setAccount(null);
      setErrorStatus(reply?.status ?? 0);
      setErrorReason(await readReason(reply));
      setStatus("error");
      return;
    }
    setAccount((await reply.json()) as AccountDocument);
    setStatus("ok");
  }

  async function onLookup(event: FormEvent) {
    event.preventDefault();
    setNotice(null);
    await loadAccount();
  }

  async function onAdjust(event: FormEvent) {
    event.preventDefault();
    if (!canWrite) return;
    const tid = tenantId.trim();
    const sid = subjectId.trim();
    const why = reason.trim();
    const qty = Number.parseInt(amount.trim(), 10);
    if (!tid || !sid || !why || !Number.isInteger(qty) || qty <= 0) return;
    setBusy(true);
    setNotice(null);
    const token =
      typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID()
        : `pts-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const body: Record<string, unknown> = {
      subjectId: sid,
      direction,
      amount: qty,
      reason: why,
      memo: memo.trim() || null,
      idempotencyToken: token,
    };
    if (referenceType.trim() && referenceId.trim()) {
      body.reference = { type: referenceType.trim(), id: referenceId.trim() };
    }
    const reply = await fetch(`/api/platform/points/adjustments?${tenantQ()}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Idempotency-Token": token,
      },
      body: JSON.stringify(body),
    }).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      const failedReason = await readReason(reply);
      setErrorReason(failedReason);
      setErrorStatus(reply?.status ?? 0);
      if (failedReason === "points-insufficient") {
        setNotice(phrases.pointsInsufficient);
      } else if (failedReason === "points-subject-not-found") {
        setNotice(phrases.pointsSubjectMissing);
      } else {
        setNotice(phrases.pointsAdjustFailed);
      }
      return;
    }
    setAccount((await reply.json()) as AccountDocument);
    setStatus("ok");
    setAmount("");
    setReason("");
    setMemo("");
    setReferenceType("");
    setReferenceId("");
    setNotice(phrases.pointsAdjustOk);
  }

  return (
    <div className="flex flex-col gap-6">
      <form onSubmit={onLookup} className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span>{phrases.pointsTenant}</span>
          <input
            value={tenantId}
            onChange={(event) => setTenantId(event.target.value)}
            className="rounded border px-2 py-1"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span>{phrases.pointsSubjectId}</span>
          <input
            value={subjectId}
            onChange={(event) => setSubjectId(event.target.value)}
            className="rounded border px-2 py-1"
          />
        </label>
        <button type="submit" className="rounded border px-3 py-1 text-sm">
          {phrases.pointsLookup}
        </button>
      </form>

      {status === "idle" ? <p className="text-sm text-muted">{phrases.pointsMissingLookup}</p> : null}
      {status === "error" ? (
        <p className="text-sm text-muted">
          {errorReason === "points-subject-not-found"
            ? phrases.pointsSubjectMissing
            : phrases.pointsLoadFailed}
          {errorStatus ? ` (${errorStatus})` : ""}
        </p>
      ) : null}
      {notice ? <p className="text-sm">{notice}</p> : null}

      {status === "ok" && account ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm">
            {phrases.pointsBalance}: <strong>{account.balance ?? 0}</strong>
          </p>
          <div>
            <h2 className="text-base font-medium">{phrases.pointsHistory}</h2>
            {!account.entries?.length ? (
              <p className="text-sm text-muted">{phrases.pointsEmptyHistory}</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-2 text-sm">
                {account.entries.map((entry) => (
                  <li key={entry.entryId} className="border-t pt-2">
                    <div>
                      {entry.direction === "DEDUCT" ? phrases.pointsEntryDeduct : phrases.pointsEntryGrant}
                      {": "}
                      {entry.delta} → {entry.balanceAfter}
                    </div>
                    <div className="text-muted">{entry.reason}</div>
                    {entry.memo ? <div className="text-muted">{entry.memo}</div> : null}
                    {entry.referenceType && entry.referenceId ? (
                      <div className="text-muted">
                        {entry.referenceType} / {entry.referenceId}
                      </div>
                    ) : null}
                    {entry.createdAt ? <div className="text-muted">{entry.createdAt}</div> : null}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      ) : null}

      {canWrite ? (
        <form onSubmit={onAdjust} className="flex max-w-md flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span>{phrases.pointsDirection}</span>
            <select
              value={direction}
              onChange={(event) => setDirection(event.target.value)}
              className="rounded border px-2 py-1"
            >
              <option value="GRANT">{phrases.pointsGrant}</option>
              <option value="DEDUCT">{phrases.pointsDeduct}</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>{phrases.pointsAmount}</span>
            <input
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              inputMode="numeric"
              className="rounded border px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>{phrases.pointsReason}</span>
            <input
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              className="rounded border px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>{phrases.pointsMemo}</span>
            <input
              value={memo}
              onChange={(event) => setMemo(event.target.value)}
              className="rounded border px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>{phrases.pointsReferenceType}</span>
            <input
              value={referenceType}
              onChange={(event) => setReferenceType(event.target.value)}
              className="rounded border px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span>{phrases.pointsReferenceId}</span>
            <input
              value={referenceId}
              onChange={(event) => setReferenceId(event.target.value)}
              className="rounded border px-2 py-1"
            />
          </label>
          <button type="submit" disabled={busy} className="rounded border px-3 py-1 text-sm">
            {phrases.pointsSubmitAdjust}
          </button>
        </form>
      ) : (
        <p className="text-sm text-muted">{phrases.pointsNoWrite}</p>
      )}
    </div>
  );
}

async function readReason(reply: Response | null): Promise<string | null> {
  if (!reply) return null;
  try {
    const body = (await reply.json()) as { reason?: string };
    return body.reason ?? null;
  } catch {
    return null;
  }
}
