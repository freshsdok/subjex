"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import type { PhraseBook } from "@/i18n/phrases";

// Sign-in failure reasons from /api/session — 登录失败原因（与 /api/session 返回的 reason 对应）。
type SignInFailure = "wrongCredentials" | "missingCredentials" | "platformUnreachable" | "platformError";

const failureByReason: Record<string, SignInFailure> = {
  "wrong-credentials": "wrongCredentials",
  "missing-credentials": "missingCredentials",
  "platform-unreachable": "platformUnreachable",
};

export function SignInForm({ phrases }: { phrases: PhraseBook }) {
  const router = useRouter();
  const [signingIn, setSigningIn] = useState(false);
  const [failure, setFailure] = useState<SignInFailure | null>(null);

  async function submitSignIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formFields = new FormData(event.currentTarget);
    setSigningIn(true);
    setFailure(null);
    try {
      const reply = await fetch("/api/session", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          loginName: formFields.get("loginName"),
          password: formFields.get("password"),
        }),
      });
      if (reply.ok) {
        router.replace("/");
        router.refresh();
        return;
      }
      const failureReply = (await reply.json().catch(() => ({}))) as { reason?: string };
      setFailure(failureByReason[failureReply.reason ?? ""] ?? "platformError");
    } catch {
      setFailure("platformUnreachable");
    } finally {
      setSigningIn(false);
    }
  }

  return (
    <form onSubmit={submitSignIn} className="flex flex-col gap-4" noValidate>
      <label className="flex flex-col gap-1 text-sm">
        {phrases.loginNameLabel}
        <input
          name="loginName"
          autoComplete="username"
          autoFocus
          className="rounded-md border border-border bg-surface px-3 py-2 text-base"
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        {phrases.passwordLabel}
        <input
          name="password"
          type="password"
          autoComplete="current-password"
          className="rounded-md border border-border bg-surface px-3 py-2 text-base"
        />
      </label>
      {failure && (
        <p role="alert" className="text-sm text-danger">
          {phrases[failure]}
        </p>
      )}
      <button
        type="submit"
        disabled={signingIn}
        className="rounded-md bg-accent px-3 py-2 font-medium text-white disabled:opacity-60"
      >
        {signingIn ? phrases.signingIn : phrases.signInAction}
      </button>
    </form>
  );
}
