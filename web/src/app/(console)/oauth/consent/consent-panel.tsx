"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

type ScopeRow = {
  name?: string;
  descriptionEn?: string;
  descriptionZh?: string;
};

type Challenge = {
  challengeId?: string;
  clientId?: string;
  clientName?: string;
  scopes?: ScopeRow[];
};

/**
 * ConsentPanel — ID-3 minimal third-party consent UI (approve / deny).
 * 第三方同意面板（批准 / 拒绝）。
 */
export function ConsentPanel({ challengeId, lang }: { challengeId: string; lang: string }) {
  const router = useRouter();
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const zh = lang.toLowerCase().startsWith("zh");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const reply = await fetch(
        `/api/platform/oauth/consent/challenges/${encodeURIComponent(challengeId)}`,
        { headers: { accept: "application/json" } },
      ).catch(() => null);
      if (cancelled) return;
      if (reply?.status === 401) {
        router.replace("/login");
        return;
      }
      if (!reply?.ok) {
        setProblem(zh ? `无法加载同意请求（${reply?.status ?? 0}）` : `Cannot load consent challenge (${reply?.status ?? 0})`);
        return;
      }
      setChallenge((await reply.json()) as Challenge);
    })();
    return () => {
      cancelled = true;
    };
  }, [challengeId, router, zh]);

  async function decide(decision: "approve" | "deny") {
    setBusy(true);
    setProblem(null);
    const reply = await fetch("/api/platform/oauth/consent/decision", {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ challengeId, decision }),
    }).catch(() => null);
    setBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(zh ? `提交失败（${reply?.status ?? 0}）` : `Decision failed (${reply?.status ?? 0})`);
      return;
    }
    const body = (await reply.json()) as { redirect_to?: string };
    if (body.redirect_to) {
      window.location.href = body.redirect_to;
      return;
    }
    setProblem(zh ? "未返回回调地址" : "No redirect_to in response");
  }

  if (problem && !challenge) {
    return <p className="text-sm text-red-600">{problem}</p>;
  }
  if (!challenge) {
    return <p className="text-sm text-muted">{zh ? "加载中…" : "Loading…"}</p>;
  }

  return (
    <div className="mt-4 max-w-lg rounded-lg border border-border bg-surface p-4">
      <p className="text-sm text-muted">
        {zh ? "应用请求访问你的账号" : "An application requests access to your account"}
      </p>
      <h2 className="mt-1 text-lg font-semibold">{challenge.clientName ?? challenge.clientId}</h2>
      <ul className="mt-4 list-disc space-y-1 pl-5 text-sm">
        {(challenge.scopes ?? []).map((s) => (
          <li key={s.name}>
            <span className="font-medium">{s.name}</span>
            <span className="text-muted"> — {zh ? s.descriptionZh : s.descriptionEn}</span>
          </li>
        ))}
      </ul>
      {problem ? <p className="mt-3 text-sm text-red-600">{problem}</p> : null}
      <div className="mt-4 flex gap-3">
        <button
          type="button"
          disabled={busy}
          onClick={() => decide("approve")}
          className="rounded bg-foreground px-3 py-1.5 text-sm text-background disabled:opacity-50"
        >
          {zh ? "批准" : "Approve"}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => decide("deny")}
          className="rounded border border-border px-3 py-1.5 text-sm disabled:opacity-50"
        >
          {zh ? "拒绝" : "Deny"}
        </button>
      </div>
    </div>
  );
}
