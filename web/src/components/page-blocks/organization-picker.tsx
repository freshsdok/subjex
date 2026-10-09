"use client";

import { useEffect, useState } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type OrganizationOption = {
  organizationId?: string;
  organizationName?: string;
};

/**
 * OrganizationPicker — 选组织（O6）。
 * With tenantId: load Organizations linked to that tenant. Otherwise editable organization-id text.
 * Context narrows candidates (tenant-linked / later: descendants, membership) — one picker only.
 * 有 tenantId 时拉租户关联 Organization；否则可编辑组织 id。不发明 organizationUnitRef 等积木。
 */
export function OrganizationPicker({
  name,
  label,
  value,
  onChange,
  tenantId,
  disabled = false,
}: {
  name: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
  tenantId?: string;
  phrases: PhraseBook;
  disabled?: boolean;
}) {
  const [organizations, setOrganizations] = useState<OrganizationOption[] | null>(null);
  const [liveFailed, setLiveFailed] = useState(false);

  useEffect(() => {
    if (!tenantId) {
      setOrganizations(null);
      setLiveFailed(false);
      return;
    }
    let cancelled = false;
    (async () => {
      const reply = await fetch(
        `/api/platform/organizations?tenantId=${encodeURIComponent(tenantId)}`,
        { headers: { accept: "application/json" } },
      ).catch(() => null);
      if (cancelled) return;
      if (!reply || reply.status === 403 || !reply.ok) {
        setLiveFailed(true);
        setOrganizations(null);
        return;
      }
      const body = (await reply.json().catch(() => null)) as {
        organizations?: OrganizationOption[];
      } | null;
      const list = Array.isArray(body?.organizations) ? body.organizations : [];
      setOrganizations(list);
      setLiveFailed(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId]);

  const useSelect =
    Boolean(tenantId) && !liveFailed && organizations !== null && organizations.length > 0;

  if (useSelect) {
    return (
      <label className="flex flex-col gap-1 text-sm">
        <span>{label}</span>
        <select
          name={name}
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          aria-label={name}
          className="rounded-md border border-border bg-background px-2 py-1"
        >
          <option value="">—</option>
          {organizations!.map((org) => {
            const id = org.organizationId ?? "";
            if (!id) return null;
            return (
              <option key={id} value={id}>
                {org.organizationName ?? id}
              </option>
            );
          })}
        </select>
      </label>
    );
  }

  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      <input
        name={name}
        type="text"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        aria-label={name}
        placeholder={name}
        className="rounded-md border border-border bg-background px-2 py-1"
      />
    </label>
  );
}
