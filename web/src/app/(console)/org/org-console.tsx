"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  ORG_MEMBERSHIP_STATE_ACTIVE,
  ORG_UNIT_STATE_ACTIVE,
  isOrgDisabledState,
  normalizeOptionalParentId,
  normalizeOrgId,
  orgTenantCookieWrite,
  toggledOrgState,
} from "@/lib/org-console";

type TenantOption = { tenantId: string; tenantName: string };

type OrganizationRow = {
  tenantId?: string;
  organizationId?: string;
  parentOrganizationId?: string | null;
  organizationName?: string;
  organizationState?: string;
};

type MembershipRow = {
  tenantId?: string;
  subjectId?: string;
  organizationId?: string;
  membershipState?: string;
};

/** Two-step write confirm state machine — 写入两步确认状态机。 */
type FormStep = "editing" | "reviewing" | "saving";

type Props = {
  phrases: PhraseBook;
  canWrite: boolean;
  tenantOptions: TenantOption[];
  initialTenantId: string;
};

/**
 * Org console — pick tenant, list Organization/Membership via /api/v1/organizations.
 * Why disabled: {@code canWrite=false} hides/locks mutating controls (need org.write).
 * Writes use FormStep editing→reviewing→saving (confirm before PUT).
 * <p>
 * 组织控制台：选租户、列组织/成员。无 org.write 时禁用写控件；写入两步确认。
 */
export function OrgConsole({ phrases, canWrite, tenantOptions, initialTenantId }: Props) {
  const router = useRouter();
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [organizations, setOrganizations] = useState<OrganizationRow[]>([]);
  const [memberships, setMemberships] = useState<MembershipRow[]>([]);
  const [listStatus, setListStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [listErrorStatus, setListErrorStatus] = useState(0);

  const [unitId, setUnitId] = useState("");
  const [unitParent, setUnitParent] = useState("");
  const [unitName, setUnitName] = useState("");
  const [unitState, setUnitState] = useState(ORG_UNIT_STATE_ACTIVE);
  const [unitStep, setUnitStep] = useState<FormStep>("editing");
  const [unitProblem, setUnitProblem] = useState<string | null>(null);
  const [unitNotice, setUnitNotice] = useState<string | null>(null);

  const [toggleUnitId, setToggleUnitId] = useState<string | null>(null);
  const [toggleStep, setToggleStep] = useState<FormStep>("editing");
  const [toggleProblem, setToggleProblem] = useState<string | null>(null);

  const [memberSubject, setMemberSubject] = useState("");
  const [memberUnit, setMemberUnit] = useState("");
  const [memberState, setMemberState] = useState(ORG_MEMBERSHIP_STATE_ACTIVE);
  const [memberStep, setMemberStep] = useState<FormStep>("editing");
  const [memberProblem, setMemberProblem] = useState<string | null>(null);
  const [memberNotice, setMemberNotice] = useState<string | null>(null);

  const [removeKey, setRemoveKey] = useState<{ subjectId: string; organizationId: string } | null>(null);
  const [removeStep, setRemoveStep] = useState<FormStep>("editing");
  const [removeProblem, setRemoveProblem] = useState<string | null>(null);

  const rememberTenant = useCallback((next: string) => {
    const trimmed = next.trim();
    setTenantId(trimmed);
    document.cookie = orgTenantCookieWrite(trimmed);
  }, []);

  const loadLists = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setOrganizations([]);
      setMemberships([]);
      setListStatus("idle");
      return;
    }
    setListStatus("loading");
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const [orgsReply, membersReply] = await Promise.all([
      fetch(`/api/platform/organizations?${q}`, { cache: "no-store" }).catch(() => null),
      fetch(`/api/platform/organizations/memberships?${q}`, { cache: "no-store" }).catch(() => null),
    ]);
    if (orgsReply?.status === 401 || membersReply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!orgsReply?.ok || !membersReply?.ok) {
      setListErrorStatus(orgsReply?.status || membersReply?.status || 0);
      setListStatus("error");
      setOrganizations([]);
      setMemberships([]);
      return;
    }
    const orgsBody = (await orgsReply.json()) as { organizations?: OrganizationRow[] };
    const membersBody = (await membersReply.json()) as { memberships?: MembershipRow[] };
    setOrganizations(orgsBody.organizations ?? []);
    setMemberships(membersBody.memberships ?? []);
    setListStatus("ok");
  }, [tenantId, router]);

  useEffect(() => {
    void loadLists();
  }, [loadLists]);

  const useTenantSelect = tenantOptions.length > 0;
  const selectOptions = useMemo(() => {
    if (!useTenantSelect) return tenantOptions;
    if (!tenantId.trim() || tenantOptions.some((t) => t.tenantId === tenantId)) return tenantOptions;
    return [{ tenantId, tenantName: tenantId }, ...tenantOptions];
  }, [useTenantSelect, tenantOptions, tenantId]);

  function reviewUnitUpsert() {
    if (!canWrite) return;
    setUnitNotice(null);
    if (!tenantId.trim()) {
      setUnitProblem(phrases.orgTenantRequired);
      return;
    }
    const id = normalizeOrgId(unitId);
    if (!id) {
      setUnitProblem(unitId.trim() ? phrases.orgIdInvalid : phrases.orgUnitIdRequired);
      return;
    }
    const parent = normalizeOptionalParentId(unitParent);
    if (parent === null) {
      setUnitProblem(phrases.orgIdInvalid);
      return;
    }
    if (!unitName.trim()) {
      setUnitProblem(phrases.orgUnitNameRequired);
      return;
    }
    setUnitProblem(null);
    setUnitStep("reviewing");
  }

  async function confirmUnitUpsert() {
    if (!canWrite) return;
    setUnitStep("saving");
    setUnitProblem(null);
    const tid = tenantId.trim();
    const id = normalizeOrgId(unitId)!;
    const parent = normalizeOptionalParentId(unitParent) ?? "";
    const name = unitName.trim();
    const state = unitState.trim() || ORG_UNIT_STATE_ACTIVE;
    const body: {
      parentOrganizationId?: string;
      organizationName: string;
      organizationState: string;
    } = {
      organizationName: name,
      organizationState: state,
    };
    if (parent) body.parentOrganizationId = parent;
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(id)}?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setUnitProblem(fillPhrase(phrases.orgUnitSaveFailed, { status: reply?.status ?? 0 }));
      setUnitStep("editing");
      return;
    }
    setUnitId("");
    setUnitParent("");
    setUnitName("");
    setUnitState(ORG_UNIT_STATE_ACTIVE);
    setUnitNotice(fillPhrase(phrases.orgUnitSavedNotice, { id, name }));
    setUnitStep("editing");
    void loadLists();
  }

  function startToggle(row: OrganizationRow) {
    if (!canWrite || !row.organizationId) return;
    setToggleProblem(null);
    setToggleUnitId(row.organizationId);
    setToggleStep("reviewing");
  }

  async function confirmToggle() {
    if (!canWrite || !toggleUnitId) return;
    const row = organizations.find((u) => u.organizationId === toggleUnitId);
    if (!row) return;
    setToggleStep("saving");
    setToggleProblem(null);
    const tid = tenantId.trim();
    const nextState = toggledOrgState(row.organizationState);
    const body: {
      parentOrganizationId?: string;
      organizationName: string;
      organizationState: string;
    } = {
      organizationName: row.organizationName ?? toggleUnitId,
      organizationState: nextState,
    };
    if (row.parentOrganizationId) body.parentOrganizationId = row.parentOrganizationId;
    const reply = await fetch(
      `/api/platform/organizations/${encodeURIComponent(toggleUnitId)}?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setToggleProblem(fillPhrase(phrases.orgUnitSaveFailed, { status: reply?.status ?? 0 }));
      setToggleStep("reviewing");
      return;
    }
    setToggleUnitId(null);
    setToggleStep("editing");
    void loadLists();
  }

  function reviewMembership() {
    if (!canWrite) return;
    setMemberNotice(null);
    if (!tenantId.trim()) {
      setMemberProblem(phrases.orgTenantRequired);
      return;
    }
    const subject = normalizeOrgId(memberSubject);
    if (!subject) {
      setMemberProblem(memberSubject.trim() ? phrases.orgIdInvalid : phrases.orgSubjectIdRequired);
      return;
    }
    const unit = normalizeOrgId(memberUnit);
    if (!unit) {
      setMemberProblem(memberUnit.trim() ? phrases.orgIdInvalid : phrases.orgMembershipUnitRequired);
      return;
    }
    setMemberProblem(null);
    setMemberStep("reviewing");
  }

  async function confirmMembership() {
    if (!canWrite) return;
    setMemberStep("saving");
    setMemberProblem(null);
    const tid = tenantId.trim();
    const subject = normalizeOrgId(memberSubject)!;
    const unit = normalizeOrgId(memberUnit)!;
    const state = memberState.trim() || ORG_MEMBERSHIP_STATE_ACTIVE;
    const reply = await fetch(
      `/api/platform/organizations/memberships?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          subjectId: subject,
          organizationId: unit,
          membershipState: state,
        }),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setMemberProblem(fillPhrase(phrases.orgMembershipSaveFailed, { status: reply?.status ?? 0 }));
      setMemberStep("editing");
      return;
    }
    setMemberSubject("");
    setMemberUnit("");
    setMemberState(ORG_MEMBERSHIP_STATE_ACTIVE);
    setMemberNotice(fillPhrase(phrases.orgMembershipSavedNotice, { subject, unit }));
    setMemberStep("editing");
    void loadLists();
  }

  function startRemove(row: MembershipRow) {
    if (!canWrite || !row.subjectId || !row.organizationId) return;
    setRemoveProblem(null);
    setRemoveKey({ subjectId: row.subjectId, organizationId: row.organizationId });
    setRemoveStep("reviewing");
  }

  async function confirmRemove() {
    if (!canWrite || !removeKey) return;
    setRemoveStep("saving");
    setRemoveProblem(null);
    const tid = tenantId.trim();
    const q = new URLSearchParams({
      tenantId: tid,
      subjectId: removeKey.subjectId,
      organizationId: removeKey.organizationId,
    });
    const reply = await fetch(`/api/platform/organizations/memberships?${q.toString()}`, {
      method: "DELETE",
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setRemoveProblem(fillPhrase(phrases.orgMembershipRemoveFailed, { status: reply?.status ?? 0 }));
      setRemoveStep("reviewing");
      return;
    }
    setRemoveKey(null);
    setRemoveStep("editing");
    void loadLists();
  }

  const reviewUnitParent = normalizeOptionalParentId(unitParent);
  const reviewUnitId = normalizeOrgId(unitId) ?? unitId.trim();

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-end gap-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted">{phrases.orgTenantLabel}</span>
          {useTenantSelect ? (
            <select
              value={tenantId}
              onChange={(event) => rememberTenant(event.target.value)}
              className="min-w-[12rem] rounded-md border border-border bg-surface px-2 py-1"
              aria-label={phrases.orgTenantLabel}
            >
              <option value="">{phrases.orgTenantPlaceholder}</option>
              {selectOptions.map((option) => (
                <option key={option.tenantId} value={option.tenantId}>
                  {option.tenantName} ({option.tenantId})
                </option>
              ))}
            </select>
          ) : (
            <input
              value={tenantId}
              onChange={(event) => rememberTenant(event.target.value)}
              placeholder={phrases.orgTenantPlaceholder}
              className="min-w-[12rem] rounded-md border border-border bg-surface px-2 py-1 font-mono text-xs"
              aria-label={phrases.orgTenantLabel}
            />
          )}
        </label>
        {!useTenantSelect && tenantId ? (
          <button
            type="button"
            onClick={() => void loadLists()}
            className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
          >
            {phrases.orgReloadAction}
          </button>
        ) : null}
      </div>

      {!tenantId.trim() ? (
        <p className="text-sm text-muted">{phrases.orgTenantRequired}</p>
      ) : listStatus === "loading" ? (
        <p className="text-sm text-muted">{phrases.orgLoading}</p>
      ) : listStatus === "error" ? (
        <p role="alert" className="text-sm text-red-700">
          {fillPhrase(phrases.loadFailed, { status: listErrorStatus })}
        </p>
      ) : (
        <>
          <section>
            <h2 className="text-sm font-medium">{phrases.orgUnitsTitle}</h2>
            <p className="mt-1 text-xs text-muted">{phrases.orgUnitsHint}</p>
            {organizations.length === 0 ? (
              <p className="mt-3 text-sm text-muted">{phrases.emptyList}</p>
            ) : (
              <table className="mt-3 w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
                <thead className="bg-background text-left text-muted">
                  <tr>
                    <th className="px-3 py-2 font-medium">{phrases.orgUnitIdColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.orgParentColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.orgNameColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.orgStateColumn}</th>
                    {canWrite ? <th className="px-3 py-2 font-medium" /> : null}
                  </tr>
                </thead>
                <tbody>
                  {organizations.map((row) => {
                    const id = row.organizationId ?? "";
                    const disabled = isOrgDisabledState(row.organizationState);
                    const reviewingThis = toggleUnitId === id && toggleStep !== "editing";
                    return (
                      <tr key={id} className="border-t border-border align-top">
                        <td className="px-3 py-2 font-mono text-xs">{row.organizationId}</td>
                        <td className="px-3 py-2 font-mono text-xs text-muted">
                          {row.parentOrganizationId || phrases.orgRootLabel}
                        </td>
                        <td className="px-3 py-2">{row.organizationName}</td>
                        <td className="px-3 py-2">{row.organizationState}</td>
                        {canWrite ? (
                          <td className="px-3 py-2">
                            {reviewingThis ? (
                              <div className="flex flex-col gap-1 text-xs">
                                <p>
                                  {fillPhrase(phrases.orgUnitToggleReview, {
                                    id,
                                    state: toggledOrgState(row.organizationState),
                                  })}
                                </p>
                                {toggleProblem ? (
                                  <p role="alert" className="text-red-700">
                                    {toggleProblem}
                                  </p>
                                ) : null}
                                <div className="flex gap-1">
                                  <button
                                    type="button"
                                    onClick={() => void confirmToggle()}
                                    disabled={toggleStep === "saving"}
                                    className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                                  >
                                    {toggleStep === "saving"
                                      ? phrases.savingAction
                                      : phrases.confirmSaveAction}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setToggleUnitId(null);
                                      setToggleStep("editing");
                                    }}
                                    disabled={toggleStep === "saving"}
                                    className="rounded-md border border-border px-2 py-0.5"
                                  >
                                    {phrases.cancelAction}
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <button
                                type="button"
                                onClick={() => startToggle(row)}
                                className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
                              >
                                {disabled ? phrases.enableAction : phrases.disableAction}
                              </button>
                            )}
                          </td>
                        ) : null}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}

            {canWrite ? (
              <div className="mt-4 max-w-lg rounded-lg border border-border bg-surface p-4">
                <h3 className="text-sm font-medium">{phrases.orgUnitUpsertTitle}</h3>
                <p className="mt-1 text-xs text-muted">{phrases.orgUnitUpsertHint}</p>
                {unitStep === "editing" ? (
                  <div className="mt-3 flex flex-col gap-3">
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgUnitIdLabel}
                      <input
                        value={unitId}
                        onChange={(e) => setUnitId(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                        autoComplete="off"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgParentLabel}
                      <input
                        value={unitParent}
                        onChange={(e) => setUnitParent(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                        placeholder={phrases.orgRootLabel}
                        autoComplete="off"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgNameLabel}
                      <input
                        value={unitName}
                        onChange={(e) => setUnitName(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1"
                        autoComplete="off"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgStateLabel}
                      <select
                        value={unitState}
                        onChange={(e) => setUnitState(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1"
                      >
                        <option value={ORG_UNIT_STATE_ACTIVE}>{ORG_UNIT_STATE_ACTIVE}</option>
                        <option value="DISABLED">DISABLED</option>
                      </select>
                    </label>
                  </div>
                ) : (
                  <div className="mt-3 space-y-1 text-xs">
                    <p className="font-medium">{phrases.orgUnitUpsertReviewTitle}</p>
                    <p>
                      {phrases.orgUnitIdLabel}: <span className="font-mono">{reviewUnitId}</span>
                    </p>
                    <p>
                      {phrases.orgParentLabel}:{" "}
                      <span className="font-mono">
                        {reviewUnitParent || phrases.orgRootLabel}
                      </span>
                    </p>
                    <p>
                      {phrases.orgNameLabel}: {unitName.trim()}
                    </p>
                    <p>
                      {phrases.orgStateLabel}: {unitState.trim() || ORG_UNIT_STATE_ACTIVE}
                    </p>
                  </div>
                )}
                {unitProblem ? (
                  <p role="alert" className="mt-2 text-xs text-red-700">
                    {unitProblem}
                  </p>
                ) : null}
                {unitNotice ? (
                  <p role="status" className="mt-2 text-xs text-green-700">
                    {unitNotice}
                  </p>
                ) : null}
                <div className="mt-3 flex gap-2">
                  {unitStep === "editing" ? (
                    <button
                      type="button"
                      onClick={reviewUnitUpsert}
                      className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background"
                    >
                      {phrases.reviewChangeAction}
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => void confirmUnitUpsert()}
                      disabled={unitStep === "saving"}
                      className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
                    >
                      {unitStep === "saving" ? phrases.savingAction : phrases.confirmSaveAction}
                    </button>
                  )}
                  {unitStep !== "editing" ? (
                    <button
                      type="button"
                      onClick={() => setUnitStep("editing")}
                      disabled={unitStep === "saving"}
                      className="rounded-md border border-border px-3 py-1.5 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  ) : null}
                </div>
              </div>
            ) : null}
          </section>

          <section>
            <h2 className="text-sm font-medium">{phrases.orgMembershipsTitle}</h2>
            <p className="mt-1 text-xs text-muted">{phrases.orgMembershipsHint}</p>
            {memberships.length === 0 ? (
              <p className="mt-3 text-sm text-muted">{phrases.emptyList}</p>
            ) : (
              <table className="mt-3 w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
                <thead className="bg-background text-left text-muted">
                  <tr>
                    <th className="px-3 py-2 font-medium">{phrases.orgSubjectColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.orgUnitIdColumn}</th>
                    <th className="px-3 py-2 font-medium">{phrases.orgStateColumn}</th>
                    {canWrite ? <th className="px-3 py-2 font-medium" /> : null}
                  </tr>
                </thead>
                <tbody>
                  {memberships.map((row) => {
                    const key = `${row.subjectId}/${row.organizationId}`;
                    const reviewingThis =
                      removeKey?.subjectId === row.subjectId &&
                      removeKey?.organizationId === row.organizationId &&
                      removeStep !== "editing";
                    return (
                      <tr key={key} className="border-t border-border align-top">
                        <td className="px-3 py-2 font-mono text-xs">{row.subjectId}</td>
                        <td className="px-3 py-2 font-mono text-xs">{row.organizationId}</td>
                        <td className="px-3 py-2">{row.membershipState}</td>
                        {canWrite ? (
                          <td className="px-3 py-2">
                            {reviewingThis ? (
                              <div className="flex flex-col gap-1 text-xs">
                                <p>
                                  {fillPhrase(phrases.orgMembershipRemoveReview, {
                                    subject: row.subjectId ?? "",
                                    unit: row.organizationId ?? "",
                                  })}
                                </p>
                                {removeProblem ? (
                                  <p role="alert" className="text-red-700">
                                    {removeProblem}
                                  </p>
                                ) : null}
                                <div className="flex gap-1">
                                  <button
                                    type="button"
                                    onClick={() => void confirmRemove()}
                                    disabled={removeStep === "saving"}
                                    className="rounded-md bg-foreground px-2 py-0.5 text-background disabled:opacity-60"
                                  >
                                    {removeStep === "saving"
                                      ? phrases.savingAction
                                      : phrases.orgMembershipRemoveConfirm}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setRemoveKey(null);
                                      setRemoveStep("editing");
                                    }}
                                    disabled={removeStep === "saving"}
                                    className="rounded-md border border-border px-2 py-0.5"
                                  >
                                    {phrases.cancelAction}
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <button
                                type="button"
                                onClick={() => startRemove(row)}
                                className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
                              >
                                {phrases.orgMembershipRemoveAction}
                              </button>
                            )}
                          </td>
                        ) : null}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}

            {canWrite ? (
              <div className="mt-4 max-w-lg rounded-lg border border-border bg-surface p-4">
                <h3 className="text-sm font-medium">{phrases.orgMembershipUpsertTitle}</h3>
                <p className="mt-1 text-xs text-muted">{phrases.orgMembershipUpsertHint}</p>
                {memberStep === "editing" ? (
                  <div className="mt-3 flex flex-col gap-3">
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgSubjectLabel}
                      <input
                        value={memberSubject}
                        onChange={(e) => setMemberSubject(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                        autoComplete="off"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgUnitIdLabel}
                      <input
                        value={memberUnit}
                        onChange={(e) => setMemberUnit(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1 font-mono"
                        autoComplete="off"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-xs">
                      {phrases.orgStateLabel}
                      <select
                        value={memberState}
                        onChange={(e) => setMemberState(e.target.value)}
                        className="rounded-md border border-border bg-background px-2 py-1"
                      >
                        <option value={ORG_MEMBERSHIP_STATE_ACTIVE}>
                          {ORG_MEMBERSHIP_STATE_ACTIVE}
                        </option>
                        <option value="DISABLED">DISABLED</option>
                      </select>
                    </label>
                  </div>
                ) : (
                  <div className="mt-3 space-y-1 text-xs">
                    <p className="font-medium">{phrases.orgMembershipUpsertReviewTitle}</p>
                    <p>
                      {phrases.orgSubjectLabel}:{" "}
                      <span className="font-mono">
                        {normalizeOrgId(memberSubject) ?? memberSubject.trim()}
                      </span>
                    </p>
                    <p>
                      {phrases.orgUnitIdLabel}:{" "}
                      <span className="font-mono">
                        {normalizeOrgId(memberUnit) ?? memberUnit.trim()}
                      </span>
                    </p>
                    <p>
                      {phrases.orgStateLabel}:{" "}
                      {memberState.trim() || ORG_MEMBERSHIP_STATE_ACTIVE}
                    </p>
                  </div>
                )}
                {memberProblem ? (
                  <p role="alert" className="mt-2 text-xs text-red-700">
                    {memberProblem}
                  </p>
                ) : null}
                {memberNotice ? (
                  <p role="status" className="mt-2 text-xs text-green-700">
                    {memberNotice}
                  </p>
                ) : null}
                <div className="mt-3 flex gap-2">
                  {memberStep === "editing" ? (
                    <button
                      type="button"
                      onClick={reviewMembership}
                      className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background"
                    >
                      {phrases.reviewChangeAction}
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => void confirmMembership()}
                      disabled={memberStep === "saving"}
                      className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
                    >
                      {memberStep === "saving" ? phrases.savingAction : phrases.confirmSaveAction}
                    </button>
                  )}
                  {memberStep !== "editing" ? (
                    <button
                      type="button"
                      onClick={() => setMemberStep("editing")}
                      disabled={memberStep === "saving"}
                      className="rounded-md border border-border px-3 py-1.5 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  ) : null}
                </div>
              </div>
            ) : null}
          </section>
        </>
      )}

    </div>
  );
}
