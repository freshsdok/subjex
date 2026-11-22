"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { PhraseBook } from "@/i18n/phrases";

/** Refresh the observation snapshot from the already-running platform process — 向已运行的平台进程重新取一份观察快照。 */
export function RateLimitsRefreshButton({ phrases }: { phrases: PhraseBook }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [clicked, setClicked] = useState(false);

  return (
    <button
      type="button"
      onClick={() => {
        setClicked(true);
        startTransition(() => router.refresh());
      }}
      disabled={pending}
      className="rounded-md border border-border px-3 py-1.5 text-xs hover:bg-background disabled:opacity-60"
    >
      {pending && clicked ? phrases.rateLimitsRefreshing : phrases.rateLimitsRefreshAction}
    </button>
  );
}
