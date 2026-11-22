"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { type PhraseBook } from "@/i18n/phrases";
import {
  endUserStatePhraseKey,
  endUsersConsoleQuery,
  type EndUserDirectoryRow,
} from "@/lib/end-user-directory";

/**
 * Tenant-scoped directory table with lookup and links to existing consent/export/session pages.
 * 按租户查阅名册，并链到已有的同意、导出与当前登录页。
 */
export function EndUserDirectoryConsole({
  phrases,
  tenants,
  selectedTenantId,
  selectedQuery,
  problem,
  users,
}: {
  phrases: PhraseBook;
  tenants: Array<{ tenantId: string; tenantName: string }>;
  selectedTenantId: string;
  selectedQuery: string;
  problem: "endUsersForbidden" | "endUsersLoadFailed" | null;
  users: EndUserDirectoryRow[];
}) {
  const router = useRouter();
  const [tenantId, setTenantId] = useState(selectedTenantId);
  const [query, setQuery] = useState(selectedQuery);

  function lookup(event: FormEvent) {
    event.preventDefault();
    router.push(endUsersConsoleQuery(tenantId, query));
  }

  function stateLabel(state: string): string {
    const key = endUserStatePhraseKey(state);
    return key ? phrases[key as keyof PhraseBook] : state;
  }

  function idpLabel(idp: EndUserDirectoryRow["idp"]): string {
    if (!idp) {
      return phrases.endUsersIdpNone;
    }
    const parts = [idp.providerId, idp.issuer].filter((part) => (part ?? "").trim());
    return parts.length > 0 ? parts.join(" · ") : phrases.endUsersIdpNone;
  }

  return (
    <div className="space-y-4">
      <form onSubmit={lookup} className="flex flex-wrap items-end gap-2 text-xs">
        <label className="flex flex-col gap-1">
          {phrases.endUsersTenantLabel}
          <select
            value={tenantId}
            onChange={(event) => setTenantId(event.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1"
          >
            <option value="">{phrases.endUsersTenantPlaceholder}</option>
            {tenants.map((tenant) => (
              <option key={tenant.tenantId} value={tenant.tenantId}>
                {tenant.tenantName ? `${tenant.tenantName} (${tenant.tenantId})` : tenant.tenantId}
              </option>
            ))}
          </select>
        </label>
        <label className="flex min-w-[16rem] flex-col gap-1">
          {phrases.endUsersQueryLabel}
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={phrases.endUsersQueryPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1"
          />
        </label>
        <button
          type="submit"
          className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-60"
          disabled={!tenantId.trim()}
        >
          {phrases.endUsersSearchAction}
        </button>
      </form>

      {!selectedTenantId ? (
        <p className="text-sm text-muted">{phrases.endUsersPickTenant}</p>
      ) : problem ? (
        <p className="text-sm text-muted">{phrases[problem]}</p>
      ) : users.length === 0 ? (
        <p className="text-sm text-muted">
          {selectedQuery ? phrases.endUsersEmptySearch : phrases.endUsersEmpty}
        </p>
      ) : (
        <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-3 py-2 font-medium">{phrases.endUsersNameColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.endUsersSubjectColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.endUsersAccountStateColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.endUsersMembershipColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.endUsersPhoneColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.endUsersIdpColumn}</th>
              <th className="px-3 py-2 font-medium">{phrases.endUsersActionsColumn}</th>
            </tr>
          </thead>
          <tbody>
            {users.map((row) => (
              <tr key={`${row.tenantId}:${row.subjectId}`} className="border-t border-border align-top">
                <td className="px-3 py-2">
                  <div>{row.displayName || phrases.sessionsUnknownPerson}</div>
                  <div className="font-mono text-xs text-muted">{row.accountId}</div>
                </td>
                <td className="px-3 py-2 font-mono text-xs">{row.subjectId}</td>
                <td className="px-3 py-2">{stateLabel(row.accountState)}</td>
                <td className="px-3 py-2">{stateLabel(row.membershipState)}</td>
                <td className="px-3 py-2 font-mono text-xs">
                  {row.phoneMasked?.trim() ? row.phoneMasked : phrases.endUsersPhoneNone}
                </td>
                <td className="px-3 py-2 text-xs">{idpLabel(row.idp)}</td>
                <td className="px-3 py-2">
                  <div className="flex flex-col items-start gap-1 text-xs">
                    <Link
                      href={`/consents?tenantId=${encodeURIComponent(row.tenantId || selectedTenantId)}&subjectId=${encodeURIComponent(row.subjectId)}`}
                      className="underline"
                    >
                      {phrases.endUsersConsentsLink}
                    </Link>
                    <Link
                      href={`/exports?tenantId=${encodeURIComponent(row.tenantId || selectedTenantId)}`}
                      className="underline"
                    >
                      {phrases.endUsersExportsLink}
                    </Link>
                    <Link
                      href={`/sessions?tenantId=${encodeURIComponent(row.tenantId || selectedTenantId)}`}
                      className="underline"
                    >
                      {phrases.endUsersSessionsLink}
                    </Link>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
