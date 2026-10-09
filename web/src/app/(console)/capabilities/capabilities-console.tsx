"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { fillPhrase, type LanguageCode, type PhraseBook } from "@/i18n/phrases";
import {
  capabilityRunPath,
  capabilitySummary,
  capabilityTitle,
  capabilityWriteBackPath,
  capabilityWriteTicketPath,
  isKnownCapabilityKind,
  type CapabilityRow,
} from "@/lib/capabilities-console";

type Props = {
  phrases: PhraseBook;
  language: LanguageCode;
  capabilities: CapabilityRow[];
};

/** Try-run / AI write-back state machine — 试跑与 AI 写回确认状态机。 */
type RunStep = "idle" | "running" | "issuing" | "confirming";

type WriteTicket = {
  capabilityId: string;
  previewText: string;
  ticketId: string;
  inputDigest: string;
  previewDigest: string;
  expiresAt: string;
};

/**
 * Capabilities console — catalog table + try-run preview.
 * AI path: issue write ticket → confirm write-back (default noop). Busy steps disable Run.
 * Deep-link helpers: {@link capabilityRunPath} / write-ticket / write-back under /api/platform.
 * <p>
 * 能力控制台：目录 + 试跑预览。AI：签发写回票→确认写回（默认 noop）。忙碌时禁用试跑。
 */
export function CapabilitiesConsole({ phrases, language, capabilities }: Props) {
  const router = useRouter();
  const [selectedId, setSelectedId] = useState<string>(capabilities[0]?.id ?? "");
  const [inputText, setInputText] = useState("");
  const [step, setStep] = useState<RunStep>("idle");
  const [result, setResult] = useState<string | null>(null);
  const [ticket, setTicket] = useState<WriteTicket | null>(null);
  const [writeNotice, setWriteNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const selected = useMemo(
    () => capabilities.find((row) => row.id === selectedId) ?? null,
    [capabilities, selectedId],
  );
  const selectedIsAi = selected != null && selected.kind === "AI";
  const busy = step !== "idle";

  function kindLabel(kind: string): string {
    if (kind === "ALGORITHM") return phrases.capabilitiesKindAlgorithm;
    if (kind === "AI") return phrases.capabilitiesKindAi;
    return kind;
  }

  function clearSelectionSideEffects() {
    setResult(null);
    setTicket(null);
    setWriteNotice(null);
    setProblem(null);
  }

  async function readErrorDetail(reply: Response): Promise<string> {
    try {
      const errBody = (await reply.json()) as { message?: string; reason?: string };
      return errBody.message ?? errBody.reason ?? "";
    } catch {
      return "";
    }
  }

  async function runSelected() {
    if (!selected) {
      setProblem(phrases.capabilitiesSelectRequired);
      return;
    }
    setStep("running");
    setProblem(null);
    setResult(null);
    setTicket(null);
    setWriteNotice(null);
    const reply = await fetch(capabilityRunPath(selected.id), {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ inputText }),
      cache: "no-store",
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply) {
      setProblem(fillPhrase(phrases.capabilitiesRunFailed, { status: 0 }));
      setStep("idle");
      return;
    }
    if (!reply.ok) {
      const detail = await readErrorDetail(reply);
      setProblem(
        detail
          ? `${fillPhrase(phrases.capabilitiesRunFailed, { status: reply.status })} ${detail}`
          : fillPhrase(phrases.capabilitiesRunFailed, { status: reply.status }),
      );
      setStep("idle");
      return;
    }
    const body = (await reply.json()) as { capabilityId?: string; result?: string };
    setResult(typeof body.result === "string" ? body.result : "");
    setStep("idle");
  }

  async function issueWriteTicket() {
    if (!selected || !selectedIsAi) {
      setProblem(phrases.capabilitiesAiOnlyWriteHint);
      return;
    }
    setStep("issuing");
    setProblem(null);
    setWriteNotice(null);
    const reply = await fetch(capabilityWriteTicketPath(selected.id), {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ inputText }),
      cache: "no-store",
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const detail = reply ? await readErrorDetail(reply) : "";
      setProblem(
        detail
          ? `${fillPhrase(phrases.capabilitiesTicketFailed, { status: reply?.status ?? 0 })} ${detail}`
          : fillPhrase(phrases.capabilitiesTicketFailed, { status: reply?.status ?? 0 }),
      );
      setStep("idle");
      return;
    }
    const body = (await reply.json()) as WriteTicket;
    setTicket(body);
    if (body.previewText) {
      setResult(body.previewText);
    }
    setStep("idle");
  }

  async function confirmWriteBack() {
    if (!selected || !selectedIsAi || !ticket) {
      setProblem(phrases.capabilitiesAiOnlyWriteHint);
      return;
    }
    setStep("confirming");
    setProblem(null);
    const reply = await fetch(capabilityWriteBackPath(selected.id), {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({
        ticketId: ticket.ticketId,
        inputDigest: ticket.inputDigest,
        previewDigest: ticket.previewDigest,
        expiresAt: ticket.expiresAt,
        previewText: ticket.previewText,
      }),
      cache: "no-store",
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const detail = reply ? await readErrorDetail(reply) : "";
      setProblem(
        detail
          ? `${fillPhrase(phrases.capabilitiesWriteBackFailed, { status: reply?.status ?? 0 })} ${detail}`
          : fillPhrase(phrases.capabilitiesWriteBackFailed, { status: reply?.status ?? 0 }),
      );
      setStep("idle");
      return;
    }
    const body = (await reply.json()) as { sink?: string; persisted?: boolean; suggestion?: string };
    setWriteNotice(
      fillPhrase(phrases.capabilitiesWriteBackNotice, {
        sink: body.sink ?? "noop",
        persisted: String(body.persisted ?? false),
      }),
    );
    if (typeof body.suggestion === "string") {
      setResult(body.suggestion);
    }
    setTicket(null);
    setStep("idle");
  }

  if (capabilities.length === 0) {
    return <p className="text-sm text-muted">{phrases.capabilitiesEmpty}</p>;
  }

  return (
    <div className="space-y-6">
      <div className="overflow-x-auto">
        <table className="w-full max-w-4xl border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.capabilitiesIdColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.capabilitiesKindColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.capabilitiesTitleColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.capabilitiesSummaryColumn}</th>
            </tr>
          </thead>
          <tbody>
            {capabilities.map((row) => {
              const active = row.id === selectedId;
              return (
                <tr
                  key={row.id}
                  className={`cursor-pointer border-t border-border ${active ? "bg-background" : ""}`}
                  onClick={() => {
                    setSelectedId(row.id);
                    clearSelectionSideEffects();
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      setSelectedId(row.id);
                      clearSelectionSideEffects();
                    }
                  }}
                  tabIndex={0}
                  aria-selected={active}
                >
                  <td className="px-3 py-2 font-mono text-xs">{row.id}</td>
                  <td className="px-3 py-2">
                    <span
                      className={`rounded-full border px-2 py-0.5 text-xs ${
                        isKnownCapabilityKind(row.kind) && row.kind === "AI"
                          ? "border-border text-muted"
                          : "border-border"
                      }`}
                    >
                      {kindLabel(row.kind)}
                    </span>
                  </td>
                  <td className="px-3 py-2 font-medium">{capabilityTitle(row, language)}</td>
                  <td className="px-3 py-2 text-muted">{capabilitySummary(row, language)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <section className="max-w-3xl rounded-lg border border-border bg-surface p-4">
        <h2 className="text-base font-semibold">{phrases.capabilitiesTryRunTitle}</h2>
        <p className="mt-1 text-sm text-muted">{phrases.capabilitiesTryRunHint}</p>
        <div className="mt-3 space-y-3">
          <label className="block text-sm">
            <span className="mb-1 block text-muted">{phrases.capabilitiesSelectLabel}</span>
            <select
              className="w-full rounded-md border border-border bg-background px-3 py-2"
              value={selectedId}
              onChange={(event) => {
                setSelectedId(event.target.value);
                clearSelectionSideEffects();
              }}
            >
              {capabilities.map((row) => (
                <option key={row.id} value={row.id}>
                  {row.id} — {capabilityTitle(row, language)}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-muted">{phrases.capabilitiesInputLabel}</span>
            <textarea
              className="min-h-28 w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-xs"
              value={inputText}
              onChange={(event) => setInputText(event.target.value)}
              placeholder={phrases.capabilitiesInputPlaceholder}
            />
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="rounded-md border border-border bg-background px-3 py-1.5 text-sm font-medium disabled:opacity-50"
              disabled={busy || !selectedId}
              onClick={() => void runSelected()}
            >
              {step === "running" ? phrases.capabilitiesRunningAction : phrases.capabilitiesRunAction}
            </button>
            {selectedIsAi ? (
              <button
                type="button"
                className="rounded-md border border-accent bg-accent/10 px-3 py-1.5 text-sm font-medium disabled:opacity-50"
                disabled={busy || !selectedId || inputText.trim() === ""}
                onClick={() => void issueWriteTicket()}
              >
                {step === "issuing"
                  ? phrases.capabilitiesIssuingTicketAction
                  : phrases.capabilitiesIssueTicketAction}
              </button>
            ) : null}
            {selected ? (
              <span className="text-xs text-muted">
                {kindLabel(selected.kind)} · {selected.id}
              </span>
            ) : null}
          </div>
          {problem ? (
            <p role="alert" className="text-sm text-danger">
              {problem}
            </p>
          ) : null}
          {writeNotice ? (
            <p role="status" className="text-sm text-green-700">
              {writeNotice}
            </p>
          ) : null}
          {result != null ? (
            <div>
              <h3 className="text-sm font-medium">{phrases.capabilitiesResultTitle}</h3>
              <pre className="mt-1 max-h-64 overflow-auto rounded-md border border-border bg-background p-3 font-mono text-xs whitespace-pre-wrap break-all">
                {result || phrases.capabilitiesResultEmpty}
              </pre>
            </div>
          ) : null}
          {ticket ? (
            <div className="rounded-md border border-accent/40 bg-accent/5 px-3 py-2 text-sm">
              <p className="font-medium">{phrases.capabilitiesTicketTitle}</p>
              <p className="mt-1 text-xs text-muted">{phrases.capabilitiesTicketHint}</p>
              <p className="mt-2 font-mono text-xs">
                {phrases.capabilitiesTicketIdLabel}: {ticket.ticketId}
              </p>
              <p className="mt-1 font-mono text-xs">
                {phrases.capabilitiesTicketExpiresLabel}: {ticket.expiresAt}
              </p>
              <button
                type="button"
                className="mt-3 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
                disabled={busy}
                onClick={() => void confirmWriteBack()}
              >
                {step === "confirming"
                  ? phrases.capabilitiesConfirmingWriteAction
                  : phrases.capabilitiesConfirmWriteAction}
              </button>
            </div>
          ) : null}
        </div>
      </section>
    </div>
  );
}
