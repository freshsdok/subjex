"use client";

import { useEffect, useState } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type MembershipOption = { subjectId?: string };

/**
 * SubjectPicker — 选主体（O6）。
 * With tenantId: load unique subjectIds from Organization memberships for that tenant.
 * Otherwise: editable subject-id text input. Context (tenant) narrows candidates — no extra picker kinds.
 * 有 tenantId 时拉该租户 Membership 的唯一 subjectId；否则可编辑主体 id。不发明更多选人积木。
 */
export function SubjectPicker({
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
  const [subjectIds, setSubjectIds] = useState<string[] | null>(null);
  const [liveFailed, setLiveFailed] = useState(false);

  useEffect(() => {
    if (!tenantId) {
      setSubjectIds(null);
      setLiveFailed(false);
      return;
    }
    let cancelled = false;
    (async () => {
      const reply = await fetch(
        `/api/platform/organizations/memberships?tenantId=${encodeURIComponent(tenantId)}`,
        { headers: { accept: "application/json" } },
      ).catch(() => null);
      if (cancelled) return;
      if (!reply || reply.status === 403 || !reply.ok) {
        setLiveFailed(true);
        setSubjectIds(null);
        return;
      }
      const body = (await reply.json().catch(() => null)) as {
        memberships?: MembershipOption[];
      } | null;
      const rows = Array.isArray(body?.memberships) ? body.memberships : [];
      const unique: string[] = [];
      const seen = new Set<string>();
      for (const row of rows) {
        const id = row.subjectId?.trim() ?? "";
        if (!id || seen.has(id)) continue;
        seen.add(id);
        unique.push(id);
      }
      setSubjectIds(unique);
      setLiveFailed(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId]);

  const useSelect =
    Boolean(tenantId) && !liveFailed && subjectIds !== null && subjectIds.length > 0;

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
          {subjectIds!.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
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

/** @deprecated O6: use SubjectPicker — 请用 SubjectPicker */
export const UserPicker = SubjectPicker;
