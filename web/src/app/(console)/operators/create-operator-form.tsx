"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  isOperatorRoleName,
  MIN_OPERATOR_PASSWORD_LENGTH,
  type OperatorRoleName,
} from "@/lib/operator-grants";

type CreateStep = "editing" | "reviewing" | "saving";

export function CreateOperatorForm({ phrases }: { phrases: PhraseBook }) {
  const router = useRouter();
  const [step, setStep] = useState<CreateStep>("editing");
  const [loginName, setLoginName] = useState("");
  const [password, setPassword] = useState("");
  const [roleName, setRoleName] = useState<OperatorRoleName>("platform-reader");
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function roleLabel(role: OperatorRoleName): string {
    return role === "platform-operator" ? phrases.operatorRoleOperator : phrases.operatorRoleReader;
  }

  function reviewCreate() {
    setNotice(null);
    if (!loginName.trim()) {
      setProblem(phrases.operatorLoginRequired);
      return;
    }
    if (password.length < MIN_OPERATOR_PASSWORD_LENGTH) {
      setProblem(phrases.operatorPasswordTooShort);
      return;
    }
    if (!isOperatorRoleName(roleName)) {
      setProblem(phrases.operatorActionFailed.replace("{status}", "role"));
      return;
    }
    setProblem(null);
    setStep("reviewing");
  }

  async function confirmCreate() {
    setStep("saving");
    setProblem(null);
    const reply = await fetch("/api/platform/operators", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        loginName: loginName.trim(),
        password,
        roleName,
      }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.createOperatorFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    const createdLogin = loginName.trim();
    setLoginName("");
    setPassword("");
    setRoleName("platform-reader");
    setNotice(fillPhrase(phrases.createOperatorCreatedNotice, { login: createdLogin, role: roleName }));
    setStep("editing");
    router.refresh();
  }

  return (
    <div className="mt-8 max-w-md rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{phrases.createOperatorTitle}</h2>
      <p className="mt-1 text-xs text-muted">{phrases.createOperatorHint}</p>
      {step === "editing" ? (
        <div className="mt-3 flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-xs">
            {phrases.loginNameLabel}
            <input
              value={loginName}
              onChange={(e) => setLoginName(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 font-mono"
              autoComplete="off"
              required
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.passwordLabel}
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
              minLength={MIN_OPERATOR_PASSWORD_LENGTH}
              autoComplete="new-password"
              required
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.operatorRoleLabel}
            <select
              value={roleName}
              onChange={(e) => setRoleName(e.target.value as OperatorRoleName)}
              className="rounded-md border border-border bg-background px-2 py-1"
            >
              <option value="platform-reader">{phrases.operatorRoleReader}</option>
              <option value="platform-operator">{phrases.operatorRoleOperator}</option>
            </select>
          </label>
        </div>
      ) : (
        <div className="mt-3 space-y-1 text-xs">
          <p className="font-medium">{phrases.createOperatorReviewTitle}</p>
          <p>
            {phrases.loginNameLabel}: <span className="font-mono">{loginName.trim()}</span>
          </p>
          <p>
            {phrases.operatorRoleLabel}: {roleLabel(roleName)}
          </p>
          <p className="text-muted">{phrases.createOperatorHint}</p>
        </div>
      )}
      {problem && (
        <p role="alert" className="mt-2 text-xs text-red-700">
          {problem}
        </p>
      )}
      {notice && (
        <p role="status" className="mt-2 text-xs text-green-700">
          {notice}
        </p>
      )}
      <div className="mt-3 flex gap-2">
        {step === "editing" ? (
          <button
            type="button"
            onClick={reviewCreate}
            className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background"
          >
            {phrases.reviewChangeAction}
          </button>
        ) : (
          <button
            type="button"
            onClick={confirmCreate}
            disabled={step === "saving"}
            className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
          >
            {step === "saving" ? phrases.createOperatorCreatingAction : phrases.createOperatorConfirmAction}
          </button>
        )}
        {step !== "editing" ? (
          <button
            type="button"
            onClick={() => setStep("editing")}
            disabled={step === "saving"}
            className="rounded-md border border-border px-3 py-1.5 text-xs"
          >
            {phrases.cancelAction}
          </button>
        ) : null}
      </div>
    </div>
  );
}
