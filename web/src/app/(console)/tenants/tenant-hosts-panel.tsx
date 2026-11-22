"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type HostRow = {
  hostName?: string;
  tenantId?: string;
  createdAt?: string;
};

/**
 * Tenant host list — bind or remove a custom hostname. Does not buy DNS or issue certificates.
 * 租户独立域名列表：登记或移除主机名。不代办 DNS，也不签发证书。
 */
export function TenantHostsPanel({
  tenantId,
  phrases,
  canManage,
}: {
  tenantId: string;
  phrases: PhraseBook;
  canManage: boolean;
}) {
  const router = useRouter();
  const [hosts, setHosts] = useState<HostRow[] | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function loadHosts() {
    setProblem(null);
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/hosts`).catch(
      () => null,
    );
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.tenantHostsLoadFailed, { status: String(reply?.status ?? 0) }));
      setHosts([]);
      return;
    }
    const body = (await reply.json()) as { hosts?: HostRow[] };
    setHosts(body.hosts ?? []);
  }

  useEffect(() => {
    void loadHosts();
    // Reload when the tenant row changes — 租户行变化时重新读取。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  async function addHost(event: FormEvent) {
    event.preventDefault();
    const hostName = draft.trim();
    if (!hostName) {
      setProblem(phrases.tenantHostsMissing);
      return;
    }
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}/hosts`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ hostName }),
      }).catch(() => null);
      if (reply?.status === 401) {
        router.replace("/login");
        return;
      }
      if (!reply?.ok) {
        setProblem(reasonFrom(reply, phrases.tenantHostsAddFailed, phrases));
        return;
      }
      setDraft("");
      setNotice(phrases.tenantHostsAddDone);
      await loadHosts();
    } finally {
      setBusy(false);
    }
  }

  async function removeHost(hostName: string) {
    if (!window.confirm(fillPhrase(phrases.tenantHostsRemoveConfirm, { host: hostName }))) {
      return;
    }
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      const reply = await fetch(
        `/api/platform/tenants/${encodeURIComponent(tenantId)}/hosts/${encodeURIComponent(hostName)}`,
        { method: "DELETE" },
      ).catch(() => null);
      if (reply?.status === 401) {
        router.replace("/login");
        return;
      }
      if (!reply?.ok) {
        setProblem(fillPhrase(phrases.tenantHostsRemoveFailed, { status: String(reply?.status ?? 0) }));
        return;
      }
      setNotice(phrases.tenantHostsRemoveDone);
      await loadHosts();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-2 w-full max-w-lg rounded-md border border-border bg-background p-3 text-xs">
      <p className="font-medium">{phrases.tenantHostsTitle}</p>
      <p className="mt-1 text-muted">{phrases.tenantHostsHint}</p>
      {hosts == null ? (
        <p className="mt-2 text-muted">{phrases.tenantHostsLoading}</p>
      ) : hosts.length === 0 ? (
        <p className="mt-2 text-muted">{phrases.tenantHostsEmpty}</p>
      ) : (
        <ul className="mt-2 space-y-1">
          {hosts.map((row) => {
            const name = row.hostName ?? "";
            return (
              <li key={name} className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-mono">{name}</span>
                {canManage ? (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void removeHost(name)}
                    className="rounded-md border border-border px-2 py-0.5 hover:bg-surface disabled:opacity-60"
                  >
                    {phrases.tenantHostsRemoveAction}
                  </button>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      {canManage ? (
        <form onSubmit={(event) => void addHost(event)} className="mt-2 flex flex-wrap items-center gap-2">
          <input
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder={phrases.tenantHostsPlaceholder}
            autoComplete="off"
            className="min-w-[12rem] flex-1 rounded-md border border-border bg-surface px-2 py-1 font-mono"
          />
          <button
            type="submit"
            disabled={busy}
            className="rounded-md border border-border px-3 py-1 hover:bg-surface disabled:opacity-60"
          >
            {busy ? phrases.tenantHostsWorking : phrases.tenantHostsAddAction}
          </button>
        </form>
      ) : null}
      {problem ? <p className="mt-2 text-red-800">{problem}</p> : null}
      {notice ? <p className="mt-2">{notice}</p> : null}
    </div>
  );
}

function reasonFrom(
  reply: Response | null,
  fallback: string,
  phrases: PhraseBook,
): string {
  // Prefer a short known case over a raw server string — 已知情况用短句，不用原样接口句子。
  const status = reply?.status ?? 0;
  if (status === 409) return phrases.tenantHostsAlreadyBound;
  if (status === 400) return phrases.tenantHostsRejected;
  return fillPhrase(fallback, { status: String(status) });
}
