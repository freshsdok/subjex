"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

export function OperatorAdminActions({
  loginName,
  accountState,
  phrases,
}: {
  loginName: string;
  accountState: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [problem, setProblem] = useState<string | null>(null);
  const disabled = accountState === "DISABLED";

  async function run(action: "disable" | "enable") {
    setProblem(null);
    const reply = await fetch(`/api/platform/operators/${encodeURIComponent(loginName)}/${action}`, {
      method: "POST",
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.operatorActionFailed, { status: reply?.status ?? 0 }));
      return;
    }
    router.refresh();
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        onClick={() => run(disabled ? "enable" : "disable")}
        className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
      >
        {disabled ? phrases.enableAction : phrases.disableAction}
      </button>
      {problem && <span className="text-xs text-red-700">{problem}</span>}
    </div>
  );
}
