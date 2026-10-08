"use client";

import { useEffect, useState } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type OrgUnitOption = { orgUnitId?: string; unitName?: string };

// OrgPicker — 选部门：有 tenantId 时拉薄组织 units；403/失败/无租户则退回可编辑文本框。
export function OrgPicker({
  name,
  label,
  value,
  onChange,
  tenantId,
}: {
  name: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
  tenantId?: string;
  phrases: PhraseBook;
}) {
  const [units, setUnits] = useState<OrgUnitOption[] | null>(null);
  const [liveFailed, setLiveFailed] = useState(false);

  useEffect(() => {
    if (!tenantId) {
      setUnits(null);
      setLiveFailed(false);
      return;
    }
    let cancelled = false;
    (async () => {
      const reply = await fetch(
        `/api/platform/org/units?tenantId=${encodeURIComponent(tenantId)}`,
        { headers: { accept: "application/json" } },
      ).catch(() => null);
      if (cancelled) return;
      if (!reply || reply.status === 403 || !reply.ok) {
        setLiveFailed(true);
        setUnits(null);
        return;
      }
      const body = (await reply.json().catch(() => null)) as { units?: OrgUnitOption[] } | null;
      const list = Array.isArray(body?.units) ? body.units : [];
      setUnits(list);
      setLiveFailed(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId]);

  const useSelect =
    Boolean(tenantId) && !liveFailed && units !== null && units.length > 0;

  if (useSelect) {
    return (
      <label className="flex flex-col gap-1 text-sm">
        <span>{label}</span>
        <select
          name={name}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          aria-label={name}
          className="rounded-md border border-border bg-background px-2 py-1"
        >
          <option value="">—</option>
          {units!.map((unit) => {
            const id = unit.orgUnitId ?? "";
            if (!id) return null;
            return (
              <option key={id} value={id}>
                {unit.unitName ?? id}
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
        onChange={(event) => onChange(event.target.value)}
        aria-label={name}
        placeholder={name}
        className="rounded-md border border-border bg-background px-2 py-1"
      />
    </label>
  );
}
