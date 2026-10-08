"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { MIN_OPERATOR_PASSWORD_LENGTH } from "@/lib/operator-grants";

type ResetStep = "closed" | "editing" | "reviewing" | "saving";

export function AdminResetPassword({
  loginName,
  phrases,
}: {
  loginName: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [step, setStep] = useState<ResetStep>("closed");
  const [newPassword, setNewPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function openEditor() {
    setNewPassword("");
    setProblem(null);
    setNotice(null);
    setStep("editing");
  }

  function reviewReset() {
    if (newPassword.length < MIN_OPERATOR_PASSWORD_LENGTH) {
      setProblem(phrases.operatorPasswordTooShort);
      return;
    }
    setProblem(null);
    setStep("reviewing");
  }

  async function confirmReset() {
    setStep("saving");
    setProblem(null);
    const reply = await fetch(`/api/platform/operators/${encodeURIComponent(loginName)}/password`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ newPassword }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.adminResetPasswordFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    setNewPassword("");
    setNotice(fillPhrase(phrases.adminResetPasswordDone, { login: loginName }));
    setStep("closed");
  }

  if (step === "closed") {
    return (
      <div className="flex flex-col gap-1">
        <button
          type="button"
          onClick={openEditor}
          className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
        >
          {phrases.adminResetPasswordAction}
        </button>
        {notice && (
          <span role="status" className="text-xs text-green-700">
            {notice}
          </span>
        )}
      </div>
    );
  }

  return (
    <div className="flex min-w-[12rem] flex-col gap-2 rounded-md border border-border bg-background p-2">
      <p className="text-xs font-medium">{fillPhrase(phrases.adminResetPasswordTitle, { login: loginName })}</p>
      {step === "editing" ? (
        <label className="flex flex-col gap-1 text-xs">
          {phrases.newPasswordLabel}
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            className="rounded-md border border-border bg-surface px-2 py-1"
            minLength={MIN_OPERATOR_PASSWORD_LENGTH}
            autoComplete="new-password"
            autoFocus
          />
        </label>
      ) : (
        <p className="text-xs">{fillPhrase(phrases.adminResetPasswordReview, { login: loginName })}</p>
      )}
      {problem && (
        <p role="alert" className="text-xs text-red-700">
          {problem}
        </p>
      )}
      <div className="flex gap-2">
        {step === "editing" ? (
          <button
            type="button"
            onClick={reviewReset}
            className="rounded-md bg-accent px-2 py-0.5 text-xs text-white"
          >
            {phrases.reviewChangeAction}
          </button>
        ) : (
          <button
            type="button"
            onClick={confirmReset}
            disabled={step === "saving"}
            className="rounded-md bg-accent px-2 py-0.5 text-xs text-white disabled:opacity-60"
          >
            {step === "saving" ? phrases.savingAction : phrases.adminResetPasswordConfirm}
          </button>
        )}
        <button
          type="button"
          onClick={() => setStep(step === "reviewing" ? "editing" : "closed")}
          disabled={step === "saving"}
          className="rounded-md border border-border px-2 py-0.5 text-xs"
        >
          {phrases.cancelAction}
        </button>
      </div>
    </div>
  );
}
