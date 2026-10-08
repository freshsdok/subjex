"use client";

import { useState, type FormEvent } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type IdpLinkDocument = {
  loginName?: string;
  issuer?: string | null;
  idpSubject?: string | null;
  linked?: boolean;
};

export function IdpLinkEditor({
  phrases,
  loginName,
  initial,
}: {
  phrases: PhraseBook;
  loginName: string;
  initial: IdpLinkDocument;
}) {
  const [issuer, setIssuer] = useState(initial.issuer ?? "");
  const [idpSubject, setIdpSubject] = useState(initial.idpSubject ?? "");
  const [linked, setLinked] = useState(Boolean(initial.linked));
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function bind(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      const reply = await fetch(`/api/platform/operators/${encodeURIComponent(loginName)}/idp-link`, {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ issuer: issuer.trim(), idpSubject: idpSubject.trim() }),
      });
      if (!reply.ok) {
        setError(fillPhrase(phrases.idpLinkFailed, { status: String(reply.status) }));
        return;
      }
      setLinked(true);
      setNotice(phrases.idpLinkBoundNotice);
    } catch {
      setError(fillPhrase(phrases.idpLinkFailed, { status: "network" }));
    } finally {
      setBusy(false);
    }
  }

  async function unlink() {
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      const reply = await fetch(`/api/platform/operators/${encodeURIComponent(loginName)}/idp-link`, {
        method: "DELETE",
      });
      if (!reply.ok) {
        setError(fillPhrase(phrases.idpLinkFailed, { status: String(reply.status) }));
        return;
      }
      setLinked(false);
      setIssuer("");
      setIdpSubject("");
      setNotice(phrases.idpLinkUnboundNotice);
    } catch {
      setError(fillPhrase(phrases.idpLinkFailed, { status: "network" }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-2 rounded border border-border bg-background p-2 text-xs">
      <p className="mb-1 font-medium">{phrases.idpLinkTitle}</p>
      <p className="mb-2 text-muted">
        {linked ? `${phrases.idpLinkBoundNotice} ${initial.issuer ?? issuer}` : phrases.idpLinkMissing}
      </p>
      <form onSubmit={bind} className="flex flex-col gap-2">
        <label className="flex flex-col gap-0.5">
          {phrases.idpIssuerLabel}
          <input
            value={issuer}
            onChange={(e) => setIssuer(e.target.value)}
            className="rounded border border-border bg-surface px-2 py-1 font-mono"
            required
          />
        </label>
        <label className="flex flex-col gap-0.5">
          {phrases.idpSubjectLabel}
          <input
            value={idpSubject}
            onChange={(e) => setIdpSubject(e.target.value)}
            className="rounded border border-border bg-surface px-2 py-1 font-mono"
            required
          />
        </label>
        <div className="flex flex-wrap gap-2">
          <button
            type="submit"
            disabled={busy}
            className="rounded bg-accent px-2 py-1 text-white disabled:opacity-60"
          >
            {phrases.idpLinkBindAction}
          </button>
          {linked && (
            <button
              type="button"
              disabled={busy}
              onClick={unlink}
              className="rounded border border-border px-2 py-1 disabled:opacity-60"
            >
              {phrases.idpLinkUnlinkAction}
            </button>
          )}
        </div>
      </form>
      {notice && <p className="mt-1 text-muted">{notice}</p>}
      {error && (
        <p role="alert" className="mt-1 text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
