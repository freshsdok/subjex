"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { fillPhrase, type LanguageCode, type PhraseBook } from "@/i18n/phrases";
import {
  capabilityRunPath,
  capabilitySummary,
  capabilityTitle,
  isKnownCapabilityKind,
  type CapabilityRow,
} from "@/lib/capabilities-console";

type Props = {
  phrases: PhraseBook;
  language: LanguageCode;
  capabilities: CapabilityRow[];
};

type RunStep = "idle" | "running";

// Capabilities console — 能力控制台：目录表 + 选中项试跑。
export function CapabilitiesConsole({ phrases, language, capabilities }: Props) {
  const router = useRouter();
  const [selectedId, setSelectedId] = useState<string>(capabilities[0]?.id ?? "");
  const [inputText, setInputText] = useState("");
  const [step, setStep] = useState<RunStep>("idle");
  const [result, setResult] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const selected = useMemo(
    () => capabilities.find((row) => row.id === selectedId) ?? null,
    [capabilities, selectedId],
  );

  function kindLabel(kind: string): string {
    if (kind === "ALGORITHM") return phrases.capabilitiesKindAlgorithm;
    if (kind === "AI") return phrases.capabilitiesKindAi;
    return kind;
  }

  async function runSelected() {
    if (!selected) {
      setProblem(phrases.capabilitiesSelectRequired);
      return;
    }
    setStep("running");
    setProblem(null);
    setResult(null);
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
      let detail = "";
      try {
        const errBody = (await reply.json()) as { message?: string; reason?: string };
        detail = errBody.message ?? errBody.reason ?? "";
      } catch {
        detail = "";
      }
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
                    setResult(null);
                    setProblem(null);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      setSelectedId(row.id);
                      setResult(null);
                      setProblem(null);
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
                setResult(null);
                setProblem(null);
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
              disabled={step === "running" || !selectedId}
              onClick={() => void runSelected()}
            >
              {step === "running" ? phrases.capabilitiesRunningAction : phrases.capabilitiesRunAction}
            </button>
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
          {result != null ? (
            <div>
              <h3 className="text-sm font-medium">{phrases.capabilitiesResultTitle}</h3>
              <pre className="mt-1 max-h-64 overflow-auto rounded-md border border-border bg-background p-3 font-mono text-xs whitespace-pre-wrap break-all">
                {result || phrases.capabilitiesResultEmpty}
              </pre>
            </div>
          ) : null}
        </div>
      </section>
    </div>
  );
}
