"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type Letter = {
  deadLetterId: string;
  tenantId: string;
  originKind: string;
  originId: string;
  failureReason: string;
  recordedAt?: string;
};

type Props = {
  phrases: PhraseBook;
  letters: Letter[];
};

/**
 * Dead-letter replay/discard controls (admin.read).
 * 死信重放/丢弃控件。
 */
export function DeadLetterActions({ phrases, letters }: Props) {
  const router = useRouter();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function act(id: string, action: "replay" | "discard") {
    setBusyId(id);
    setProblem(null);
    setNotice(null);
    const reply = await fetch(`/api/platform/dead-letters/${encodeURIComponent(id)}/${action}`, {
      method: "POST",
      headers: { accept: "application/json" },
    }).catch(() => null);
    setBusyId(null);
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.deadLetterActionFailed, { status: reply?.status ?? 0, action }));
      return;
    }
    setNotice(
      action === "replay" ? phrases.deadLetterReplayDone : phrases.deadLetterDiscardDone,
    );
    router.refresh();
  }

  if (letters.length === 0) {
    return <p className="text-sm text-muted">{phrases.emptyList}</p>;
  }

  return (
    <div className="space-y-3">
      {notice ? (
        <p role="status" className="text-xs text-green-700">
          {notice}
        </p>
      ) : null}
      {problem ? (
        <p role="alert" className="text-xs text-red-700">
          {problem}
        </p>
      ) : null}
      <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
        <thead className="bg-background text-left text-muted">
          <tr>
            <th className="px-3 py-2 font-medium">{phrases.deadLetterIdColumn}</th>
            <th className="px-3 py-2 font-medium">{phrases.tenantColumn}</th>
            <th className="px-3 py-2 font-medium">{phrases.deadLetterOriginColumn}</th>
            <th className="px-3 py-2 font-medium">{phrases.deadLetterReasonColumn}</th>
            <th className="px-3 py-2 font-medium">{phrases.actionsColumn}</th>
          </tr>
        </thead>
        <tbody>
          {letters.map((letter) => {
            const canReplay = letter.originKind === "OUTBOX_EVENT";
            const busy = busyId === letter.deadLetterId;
            return (
              <tr key={letter.deadLetterId} className="border-t border-border">
                <td className="px-3 py-2 font-mono text-xs">{letter.deadLetterId}</td>
                <td className="px-3 py-2 font-mono text-xs">{letter.tenantId}</td>
                <td className="px-3 py-2">
                  {letter.originKind}
                  <div className="font-mono text-[10px] text-muted">{letter.originId}</div>
                </td>
                <td className="px-3 py-2 text-xs">{letter.failureReason}</td>
                <td className="px-3 py-2">
                  <div className="flex flex-wrap gap-1">
                    {canReplay ? (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void act(letter.deadLetterId, "replay")}
                        className="rounded-md bg-foreground px-2 py-0.5 text-xs text-background disabled:opacity-60"
                      >
                        {phrases.deadLetterReplayAction}
                      </button>
                    ) : null}
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void act(letter.deadLetterId, "discard")}
                      className="rounded-md border border-border px-2 py-0.5 text-xs disabled:opacity-60"
                    >
                      {phrases.deadLetterDiscardAction}
                    </button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
