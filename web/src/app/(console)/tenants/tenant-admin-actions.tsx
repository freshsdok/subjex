"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { EditTenantName } from "./edit-tenant-name";

export function TenantAdminActions({
  tenantId,
  tenantName,
  tenantState,
  phrases,
}: {
  tenantId: string;
  tenantName: string;
  tenantState: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [problem, setProblem] = useState<string | null>(null);
  const suspended = tenantState === "SUSPENDED";

  async function run(action: "disable" | "enable") {
    setProblem(null);
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/${action}`, {
      method: "POST",
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.tenantActionFailed, { status: reply?.status ?? 0 }));
      return;
    }
    router.refresh();
  }

  return (
    <div className="flex flex-col items-start gap-2">
      <div className="flex flex-wrap gap-1">
        <button
          type="button"
          onClick={() => run(suspended ? "enable" : "disable")}
          className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
        >
          {suspended ? phrases.enableAction : phrases.disableAction}
        </button>
      </div>
      <EditTenantName tenantId={tenantId} currentName={tenantName} phrases={phrases} />
      {problem && <span className="text-xs text-red-700">{problem}</span>}
    </div>
  );
}
