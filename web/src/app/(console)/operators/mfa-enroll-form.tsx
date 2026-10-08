"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type StartDoc = { secret?: string; otpauthUri?: string };
type ConfirmDoc = { recoveryCodes?: string[] };

export function MfaEnrollForm({
  phrases,
  enrolled,
}: {
  phrases: PhraseBook;
  enrolled: boolean;
}) {
  const router = useRouter();
  const [phase, setPhase] = useState<"idle" | "pending" | "recovery" | "disable">("idle");
  const [secret, setSecret] = useState("");
  const [otpauthUri, setOtpauthUri] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function startEnroll() {
    setBusy(true);
    setProblem(null);
    setNotice(null);
    const reply = await fetch("/api/platform/auth/mfa/totp/start", { method: "POST" }).catch(() => null);
    setBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 409) {
      setProblem(phrases.mfaAlreadyEnrolled);
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.mfaActionFailed, { status: reply?.status ?? 0 }));
      return;
    }
    const body = (await reply.json()) as StartDoc;
    setSecret(body.secret ?? "");
    setOtpauthUri(body.otpauthUri ?? "");
    setPhase("pending");
  }

  async function confirmEnroll(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    const reply = await fetch("/api/platform/auth/mfa/totp/confirm", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code }),
    }).catch(() => null);
    setBusy(false);
    if (reply?.status === 401) {
      setProblem(phrases.invalidMfa);
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.mfaActionFailed, { status: reply?.status ?? 0 }));
      return;
    }
    const body = (await reply.json()) as ConfirmDoc;
    setRecoveryCodes(body.recoveryCodes ?? []);
    setPhase("recovery");
    setCode("");
  }

  async function disableMfa(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    setNotice(null);
    const reply = await fetch("/api/platform/auth/mfa/totp/disable", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ password, code }),
    }).catch(() => null);
    setBusy(false);
    if (reply?.status === 401) {
      setProblem(phrases.mfaDisableUnauthorized);
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.mfaActionFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setPassword("");
    setCode("");
    setPhase("idle");
    setNotice(phrases.mfaDisabledNotice);
    router.refresh();
  }

  return (
    <div className="mt-4 flex max-w-lg flex-col gap-3 rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{phrases.mfaTitle}</h2>
      <p className="text-xs text-muted">{phrases.mfaHint}</p>
      {enrolled && phase === "idle" ? (
        <>
          <p className="text-xs text-green-700">{phrases.mfaEnrolledNotice}</p>
          <button
            type="button"
            className="w-fit rounded-md border border-border px-3 py-1.5 text-xs"
            onClick={() => {
              setPhase("disable");
              setProblem(null);
              setNotice(null);
            }}
          >
            {phrases.mfaDisableAction}
          </button>
        </>
      ) : null}
      {!enrolled && phase === "idle" ? (
        <button
          type="button"
          disabled={busy}
          className="w-fit rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
          onClick={startEnroll}
        >
          {busy ? phrases.savingAction : phrases.mfaStartAction}
        </button>
      ) : null}
      {phase === "pending" ? (
        <form onSubmit={confirmEnroll} className="flex flex-col gap-3">
          <p className="text-xs text-muted">{phrases.mfaScanHint}</p>
          <p className="break-all font-mono text-[11px] text-muted">{otpauthUri}</p>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.mfaSecretLabel}
            <input
              readOnly
              value={secret}
              className="rounded-md border border-border bg-background px-2 py-1 font-mono"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.mfaCodeLabel}
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoComplete="one-time-code"
              inputMode="numeric"
              className="rounded-md border border-border bg-background px-2 py-1"
              required
            />
          </label>
          <button
            type="submit"
            disabled={busy}
            className="w-fit rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
          >
            {busy ? phrases.savingAction : phrases.mfaConfirmAction}
          </button>
        </form>
      ) : null}
      {phase === "recovery" ? (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-muted">{phrases.mfaRecoveryHint}</p>
          <ul className="rounded-md border border-border bg-background p-2 font-mono text-xs">
            {recoveryCodes.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <button
            type="button"
            className="w-fit rounded-md bg-foreground px-3 py-1.5 text-xs text-background"
            onClick={() => {
              setPhase("idle");
              setRecoveryCodes([]);
              router.refresh();
            }}
          >
            {phrases.mfaRecoveryAck}
          </button>
        </div>
      ) : null}
      {phase === "disable" ? (
        <form onSubmit={disableMfa} className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-xs">
            {phrases.currentPasswordLabel}
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
              required
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.mfaCodeLabel}
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
              required
            />
          </label>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={busy}
              className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
            >
              {busy ? phrases.savingAction : phrases.mfaDisableConfirm}
            </button>
            <button
              type="button"
              className="rounded-md border border-border px-3 py-1.5 text-xs"
              onClick={() => setPhase("idle")}
            >
              {phrases.cancelAction}
            </button>
          </div>
        </form>
      ) : null}
      {problem && <p className="text-xs text-red-700">{problem}</p>}
      {notice && (
        <p role="status" className="text-xs text-green-700">
          {notice}
        </p>
      )}
    </div>
  );
}
