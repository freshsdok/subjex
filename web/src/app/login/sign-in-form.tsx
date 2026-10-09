"use client";

/**
 * Sign-in form — password (+ optional MFA) and OIDC start.
 * User path: submit → /api/session cookie → redirect /; MFA challenge stays on this page.
 * Deep links: {@code ?oidc=unlinked|denied|disabled|...} surfaces SSO errors after callback.
 * Why SSO hidden: /api/session/oidc/status not enabled (probe failure keeps password path).
 * <p>
 * 登录表单：口令（+ 可选 MFA）与 OIDC。成功写会话 cookie 后进概览；MFA 挑战留在本页。
 * 深链：?oidc= 展示回调错误。SSO 隐藏：状态未启用（探测失败仍可用口令）。
 */

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import type { PhraseBook } from "@/i18n/phrases";

// Sign-in failure reasons from /api/session — 登录失败原因（与 /api/session 返回的 reason 对应）。
type SignInFailure =
  | "wrongCredentials"
  | "missingCredentials"
  | "platformUnreachable"
  | "platformError"
  | "invalidMfa"
  | "mfaEnrollmentRequired"
  | "missingMfa"
  | "oidcUnlinked"
  | "oidcDenied"
  | "oidcDisabled"
  | "oidcError";

const failureByReason: Record<string, SignInFailure> = {
  "wrong-credentials": "wrongCredentials",
  "missing-credentials": "missingCredentials",
  "platform-unreachable": "platformUnreachable",
  "invalid-mfa": "invalidMfa",
  "mfa-enrollment-required": "mfaEnrollmentRequired",
  "missing-mfa": "missingMfa",
};

const oidcFailureByQuery: Record<string, SignInFailure> = {
  unlinked: "oidcUnlinked",
  denied: "oidcDenied",
  disabled: "oidcDisabled",
  unreachable: "platformUnreachable",
  error: "oidcError",
};

export function SignInForm({ phrases }: { phrases: PhraseBook }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [signingIn, setSigningIn] = useState(false);
  const [failure, setFailure] = useState<SignInFailure | null>(null);
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [oidcEnabled, setOidcEnabled] = useState(false);
  const [passwordVisible, setPasswordVisible] = useState(false);

  useEffect(() => {
    const oidc = searchParams.get("oidc");
    if (oidc && oidcFailureByQuery[oidc]) {
      setFailure(oidcFailureByQuery[oidc]);
    }
    let cancelled = false;
    fetch("/api/session/oidc/status", { cache: "no-store" })
      .then(async (reply) => {
        if (!reply.ok) return;
        const body = (await reply.json()) as { enabled?: unknown };
        if (!cancelled && body.enabled === true) setOidcEnabled(true);
      })
      .catch(() => {
        // Status probe failure: hide SSO button; password path remains.
        // 状态探测失败：隐藏 SSO，仍可用口令。
      });
    return () => {
      cancelled = true;
    };
  }, [searchParams]);

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
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
        const body = (await reply.json().catch(() => ({}))) as {
          mfaRequired?: boolean;
          mfaToken?: string;
        };
        if (body.mfaRequired && body.mfaToken) {
          setMfaToken(body.mfaToken);
          setSigningIn(false);
          return;
        }
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

  async function submitMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!mfaToken) return;
    const formFields = new FormData(event.currentTarget);
    const code = String(formFields.get("mfaCode") ?? "").trim();
    setSigningIn(true);
    setFailure(null);
    try {
      const reply = await fetch("/api/session/mfa", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ mfaToken, code }),
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

  if (mfaToken) {
    return (
      <form onSubmit={submitMfa} className="flex flex-col gap-4" noValidate>
        <p className="text-sm text-muted">{phrases.mfaChallengeHint}</p>
        <label className="flex flex-col gap-1 text-sm">
          {phrases.mfaCodeLabel}
          <input
            name="mfaCode"
            autoComplete="one-time-code"
            inputMode="numeric"
            autoFocus
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
          {signingIn ? phrases.signingIn : phrases.mfaVerifyAction}
        </button>
        <button
          type="button"
          className="text-sm text-muted underline"
          onClick={() => {
            setMfaToken(null);
            setFailure(null);
          }}
        >
          {phrases.mfaBackToPassword}
        </button>
      </form>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={submitPassword} className="flex flex-col gap-4" noValidate>
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
          <span className="relative block">
            <input
              name="password"
              type={passwordVisible ? "text" : "password"}
              autoComplete="current-password"
              className="w-full rounded-md border border-border bg-surface py-2 pl-3 pr-10 text-base"
            />
            <button
              type="button"
              className="absolute inset-y-0 right-0 flex items-center px-2.5 text-muted hover:text-foreground"
              aria-label={passwordVisible ? phrases.hidePassword : phrases.showPassword}
              aria-pressed={passwordVisible}
              onClick={() => setPasswordVisible((v) => !v)}
            >
              {passwordVisible ? (
                /* eye-off — 隐藏 */
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.75"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="h-5 w-5"
                  aria-hidden="true"
                >
                  <path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49" />
                  <path d="M14.084 14.158a3 3 0 0 1-4.242-4.242" />
                  <path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143" />
                  <path d="m2 2 20 20" />
                </svg>
              ) : (
                /* eye — 显示 */
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.75"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="h-5 w-5"
                  aria-hidden="true"
                >
                  <path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
              )}
            </button>
          </span>
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
      {oidcEnabled && (
        <>
          <div className="flex items-center gap-2 text-xs text-muted">
            <span className="h-px flex-1 bg-border" />
            <span>{phrases.oidcOrDivider}</span>
            <span className="h-px flex-1 bg-border" />
          </div>
          <a
            href="/api/session/oidc/start"
            className="rounded-md border border-border bg-background px-3 py-2 text-center text-sm font-medium hover:bg-surface"
          >
            {phrases.oidcSignInAction}
          </a>
          <p className="text-xs text-muted">{phrases.oidcSignInHint}</p>
        </>
      )}
    </div>
  );
}
