"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { AdminResetPassword } from "./admin-reset-password";
import { TenantGrantsEditor } from "./tenant-grants-editor";

export function OperatorAdminActions({
  loginName,
  accountState,
  tenantIds,
  phrases,
}: {
  loginName: string;
  accountState: string;
  tenantIds: string[];
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
    <div className="flex flex-col items-start gap-2">
      <div className="flex flex-wrap gap-1">
        <button
          type="button"
          onClick={() => run(disabled ? "enable" : "disable")}
          className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
        >
          {disabled ? phrases.enableAction : phrases.disableAction}
        </button>
      </div>
      <AdminResetPassword loginName={loginName} phrases={phrases} />
      <TenantGrantsEditor loginName={loginName} initialTenantIds={tenantIds} phrases={phrases} />
      {problem && <span className="text-xs text-red-700">{problem}</span>}
    </div>
  );
}
