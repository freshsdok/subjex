"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { fillPhrase, type LanguageCode, type PhraseBook } from "@/i18n/phrases";
import {
  DECLARATION_KINDS,
  canApplyMigration,
  canCancelMigration,
  canGuidedReviewApplyMigration,
  canOfferDeclarationMigrate,
  canOfferDeclarationPromote,
  canReviewMigration,
  filterMigrateAuditEntries,
  isFailedMigration,
  migrationPathChecklist,
  type MigrationPathChipState,
  type MigrationPathStep,
  declarationMigrateEnqueueBody,
  declarationPromoteRequestBody,
  declarationTenantCookieWrite,
  isClasspathSampleKey,
  mergeDraftAndClasspathKeys,
  templateYamlFor,
  type DeclarationKind,
} from "@/lib/declaration-draft";
import {
  buildBusinessTableDrafts,
  type BusinessTableWizardState,
} from "@/lib/business-table-wizard";
import {
  applyFlowBlocksToYaml,
  emptyFlowPageBlocks,
  parseFlowBlocksFromYaml,
  type FlowPageBlocks,
} from "@/lib/flow-block-composer";
import {
  applyEntityWizardToYaml,
  emptyEntityWizard,
  parseEntityWizardFromYaml,
  type EntityWizardState,
} from "@/lib/entity-wizard";
import {
  applyFormWizardToYaml,
  emptyFormWizard,
  parseFormWizardFromYaml,
  type FormWizardState,
} from "@/lib/form-wizard";
import { BusinessTableWizard } from "./business-table-wizard";
import { EntityWizard } from "./entity-wizard";
import { FlowBlockComposer } from "./flow-block-composer";
import { FormWizard } from "./form-wizard";

type TenantOption = { tenantId: string; tenantName: string };

type RevisionRow = {
  tenantId?: string;
  declarationKind?: string;
  declarationKey?: string;
  revision?: number;
  yamlBody?: string;
  draftState?: string;
  updatedAt?: string;
  updatedBySubjectId?: string;
  /** MigUX-2b: ids from entity PUT auto-enqueue (RT-4). */
  enqueuedMigrationIds?: string[];
};

type EffectiveSummary = {
  declarationKind?: string;
  key?: string;
  version?: number;
  fieldNames?: string[];
  fromDraft?: boolean;
};

type EditorStep = "editing" | "reviewing" | "saving" | "reviewing-promote" | "promoting";

type PromoteHistoryRow = {
  tenantId?: string;
  declarationKind?: string;
  declarationKey?: string;
  revision?: number;
  gitCommitSha?: string;
  promotedAt?: string;
  promotedBySubjectId?: string;
};

type MigrationRow = {
  migrationId?: string;
  tenantId?: string;
  declarationKind?: string;
  declarationKey?: string;
  declarationRevision?: number;
  sqlText?: string;
  status?: string;
  createdAt?: string;
  createdBySubjectId?: string;
  updatedAt?: string;
  appliedAt?: string;
  errorMessage?: string;
};

type MigrateAuditRow = {
  auditEntryId?: string;
  occurredAt?: string;
  actorLogin?: string;
  actor?: string;
  actionName?: string;
  actionWordZh?: string;
  actionWordEn?: string;
  actionTarget?: string;
  outcome?: string;
  outcomeWordZh?: string;
  outcomeWordEn?: string;
};

type MigrateStep =
  | { phase: "idle" }
  | { phase: "review-enqueue" }
  | { phase: "enqueueing" }
  | { phase: "review-review"; migrationId: string }
  | { phase: "reviewing"; migrationId: string }
  | { phase: "review-apply"; migrationId: string }
  | { phase: "applying"; migrationId: string }
  | { phase: "review-guided"; migrationId: string }
  | { phase: "guiding"; migrationId: string }
  | { phase: "review-cancel"; migrationId: string }
  | { phase: "cancelling"; migrationId: string };

type Props = {
  phrases: PhraseBook;
  language: LanguageCode;
  canWrite: boolean;
  canPromote: boolean;
  canMigrate: boolean;
  tenantOptions: TenantOption[];
  initialTenantId: string;
};

function kindLabel(phrases: PhraseBook, kind: DeclarationKind): string {
  switch (kind) {
    case "entity":
      return phrases.declarationsKindEntity;
    case "form":
      return phrases.declarationsKindForm;
    case "flow":
      return phrases.declarationsKindFlow;
  }
}

function normalizeKey(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/\s/.test(trimmed) || trimmed.includes("/")) return null;
  return trimmed;
}

// Declarations console — 声明草稿控制台：选租户/种类、列表、YAML 编辑（核对后保存/晋升）、生效摘要与晋升历史。
export function DeclarationsConsole({ phrases, language, canWrite, canPromote, canMigrate, tenantOptions, initialTenantId }: Props) {
  const router = useRouter();
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [kind, setKind] = useState<DeclarationKind>("entity");
  const [revisions, setRevisions] = useState<RevisionRow[]>([]);
  const [listStatus, setListStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [listErrorStatus, setListErrorStatus] = useState(0);

  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [yamlBody, setYamlBody] = useState("");
  const [loadedRevision, setLoadedRevision] = useState<number | null>(null);
  const [editorStep, setEditorStep] = useState<EditorStep>("editing");
  const [editorProblem, setEditorProblem] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);
  const [businessTableNotice, setBusinessTableNotice] = useState<string | null>(null);
  const [draftState, setDraftState] = useState<string | null>(null);
  const [promoteHistory, setPromoteHistory] = useState<PromoteHistoryRow[] | null>(null);
  const [historyStatus, setHistoryStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [historyErrorStatus, setHistoryErrorStatus] = useState(0);

  const [migrations, setMigrations] = useState<MigrationRow[] | null>(null);
  const [migrateStatus, setMigrateStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [migrateErrorStatus, setMigrateErrorStatus] = useState(0);
  const [migrateSql, setMigrateSql] = useState("");
  const [migrateRevision, setMigrateRevision] = useState<string>("");
  const [migrateStep, setMigrateStep] = useState<MigrateStep>({ phase: "idle" });
  const [migrateProblem, setMigrateProblem] = useState<string | null>(null);
  const [migrateNotice, setMigrateNotice] = useState<string | null>(null);
  const [showManualMigrateEnqueue, setShowManualMigrateEnqueue] = useState(false);
  const [migrateAudit, setMigrateAudit] = useState<MigrateAuditRow[] | null>(null);
  const [migrateAuditStatus, setMigrateAuditStatus] = useState<"idle" | "loading" | "ok" | "forbidden" | "error">("idle");

  const [newKey, setNewKey] = useState("");
  const [effective, setEffective] = useState<EffectiveSummary | null>(null);
  const [effectiveProblem, setEffectiveProblem] = useState<string | null>(null);
  const [composerBlocks, setComposerBlocks] = useState<FlowPageBlocks>(emptyFlowPageBlocks);
  const [entityWizard, setEntityWizard] = useState<EntityWizardState>(() => emptyEntityWizard());
  const [formWizard, setFormWizard] = useState<FormWizardState>(() => emptyFormWizard());
  const [composerNotice, setComposerNotice] = useState<string | null>(null);

  function syncWizardsFromYaml(yaml: string, forKind: DeclarationKind) {
    setComposerNotice(null);
    if (forKind === "flow") {
      setComposerBlocks(parseFlowBlocksFromYaml(yaml));
      return;
    }
    if (forKind === "entity") {
      setEntityWizard(parseEntityWizardFromYaml(yaml));
      return;
    }
    setFormWizard(parseFormWizardFromYaml(yaml));
  }

  function applyComposerToYaml() {
    if (kind !== "flow") return;
    const next = applyFlowBlocksToYaml(yamlBody, composerBlocks);
    setYamlBody(next);
    setComposerNotice(phrases.declarationsComposerAppliedNotice);
  }

  function applyEntityWizard() {
    if (kind !== "entity") return;
    const locked = selectedKey
      ? { ...entityWizard, entityKey: selectedKey }
      : entityWizard;
    const next = applyEntityWizardToYaml(yamlBody, locked);
    setYamlBody(next);
    setEntityWizard(parseEntityWizardFromYaml(next));
    setComposerNotice(phrases.declarationsWizardAppliedNotice);
  }

  function applyFormWizard() {
    if (kind !== "form") return;
    const locked = selectedKey ? { ...formWizard, formKey: selectedKey } : formWizard;
    const next = applyFormWizardToYaml(yamlBody, locked);
    setYamlBody(next);
    setFormWizard(parseFormWizardFromYaml(next));
    setComposerNotice(phrases.declarationsWizardAppliedNotice);
  }

  const rememberTenant = useCallback((next: string) => {
    const trimmed = next.trim();
    setTenantId(trimmed);
    document.cookie = declarationTenantCookieWrite(trimmed);
  }, []);

  const loadList = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setRevisions([]);
      setListStatus("idle");
      return;
    }
    setListStatus("loading");
    const reply = await fetch(
      `/api/platform/declarations?tenantId=${encodeURIComponent(tid)}&kind=${encodeURIComponent(kind)}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setListErrorStatus(reply?.status ?? 0);
      setListStatus("error");
      setRevisions([]);
      return;
    }
    const body = (await reply.json()) as { revisions?: RevisionRow[] };
    setRevisions(body.revisions ?? []);
    setListStatus("ok");
  }, [tenantId, kind, router]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  useEffect(() => {
    if (loadedRevision != null) {
      setMigrateRevision(String(loadedRevision));
    }
  }, [loadedRevision, selectedKey]);

  const draftByKey = useMemo(() => {
    const map = new Map<string, RevisionRow>();
    for (const row of revisions) {
      const key = row.declarationKey ?? "";
      if (key) map.set(key, row);
    }
    return map;
  }, [revisions]);

  const listRows = useMemo(
    () => mergeDraftAndClasspathKeys([...draftByKey.keys()], kind),
    [draftByKey, kind],
  );

  async function openKey(key: string, seedYaml?: string, kindOverride?: DeclarationKind) {
    const tid = tenantId.trim();
    if (!tid) {
      setEditorProblem(phrases.declarationsTenantRequired);
      return;
    }
    const activeKind = kindOverride ?? kind;
    if (kindOverride && kindOverride !== kind) {
      setKind(kindOverride);
    }
    setSelectedKey(key);
    setEditorStep("editing");
    setEditorProblem(null);
    setSavedMessage(null);
    setDraftState(null);
    setPromoteHistory(null);
    setHistoryStatus("idle");
    setMigrations(null);
    setMigrateStatus("idle");
    setMigrateErrorStatus(0);
    setMigrateSql("");
    setMigrateRevision("");
    setMigrateStep({ phase: "idle" });
    setMigrateProblem(null);
    setMigrateNotice(null);
    setShowManualMigrateEnqueue(false);
    setMigrateAudit(null);
    setMigrateAuditStatus("idle");
    setEffective(null);
    setEffectiveProblem(null);

    const existing = draftByKey.get(key);
    if (existing?.yamlBody != null) {
      setYamlBody(existing.yamlBody);
      syncWizardsFromYaml(existing.yamlBody, activeKind);
      setLoadedRevision(existing.revision ?? null);
      setDraftState(existing.draftState ?? null);
      if (activeKind === "entity") {
        await loadMigrations(key, activeKind);
        void loadPromoteHistoryForKey(key, activeKind);
      }
      return;
    }

    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(activeKind)}/${encodeURIComponent(key)}?tenantId=${encodeURIComponent(tid)}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.ok) {
      const body = (await reply.json()) as RevisionRow;
      const yaml = body.yamlBody ?? "";
      setYamlBody(yaml);
      syncWizardsFromYaml(yaml, activeKind);
      setLoadedRevision(body.revision ?? null);
      setDraftState(body.draftState ?? null);
      if (activeKind === "entity") {
        await loadMigrations(key, activeKind);
        void loadPromoteHistoryForKey(key, activeKind);
      }
      return;
    }
    if (reply?.status === 404) {
      const yaml = seedYaml ?? templateYamlFor(activeKind, key);
      setYamlBody(yaml);
      syncWizardsFromYaml(yaml, activeKind);
      setLoadedRevision(null);
      setDraftState(null);
      return;
    }
    const yaml = seedYaml ?? templateYamlFor(activeKind, key);
    setYamlBody(yaml);
    syncWizardsFromYaml(yaml, activeKind);
    setLoadedRevision(null);
    setDraftState(null);
    if (reply && reply.status !== 404) {
      setEditorProblem(fillPhrase(phrases.declarationsLoadFailed, { status: reply.status }));
    }
  }


  async function createBusinessTableDrafts(state: BusinessTableWizardState) {
    const tid = tenantId.trim();
    if (!tid) {
      throw new Error(phrases.declarationsTenantRequired);
    }
    const bundle = buildBusinessTableDrafts(state);
    const puts: { kind: DeclarationKind; yaml: string }[] = [
      { kind: "entity", yaml: bundle.entityYaml },
      { kind: "form", yaml: bundle.formYaml },
      { kind: "flow", yaml: bundle.flowYaml },
    ];
    for (const item of puts) {
      const reply = await fetch(
        `/api/platform/declarations/${encodeURIComponent(item.kind)}/${encodeURIComponent(bundle.key)}?tenantId=${encodeURIComponent(tid)}`,
        {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ yamlBody: item.yaml }),
        },
      ).catch(() => null);
      if (reply?.status === 401) {
        router.replace("/login");
        throw new Error(phrases.declarationsBusinessTableSaveFailed);
      }
      if (!reply?.ok) {
        throw new Error(
          fillPhrase(phrases.declarationsBusinessTablePartialFailed, {
            kind: item.kind,
            key: bundle.key,
            status: reply?.status ?? 0,
          }),
        );
      }
    }
    await loadList();
    await openKey(bundle.key, undefined, "entity");
    setSavedMessage(
      fillPhrase(phrases.declarationsBusinessTableSavedNotice, { key: bundle.key }),
    );
    setBusinessTableNotice(
      fillPhrase(phrases.declarationsBusinessTableSavedNotice, { key: bundle.key }),
    );
  }

  function startNewDraft() {
    const key = normalizeKey(newKey);
    if (!key) {
      setEditorProblem(
        newKey.trim() ? phrases.declarationsNewKeyInvalid : phrases.declarationsNewKeyRequired,
      );
      return;
    }
    if (!tenantId.trim()) {
      setEditorProblem(phrases.declarationsTenantRequired);
      return;
    }
    setEditorProblem(null);
    void openKey(key, templateYamlFor(kind, key));
  }

  function reviewSave() {
    if (!canWrite) return;
    if (!selectedKey) return;
    if (!tenantId.trim()) return setEditorProblem(phrases.declarationsTenantRequired);
    if (yamlBody.trim() === "") return setEditorProblem(phrases.declarationsBlankYaml);
    setEditorProblem(null);
    setEditorStep("reviewing");
  }

  async function confirmSave() {
    if (!canWrite || !selectedKey) return;
    const tid = tenantId.trim();
    setEditorStep("saving");
    setEditorProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey)}?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ yamlBody }),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setEditorProblem(fillPhrase(phrases.declarationsSaveFailed, { status: reply?.status ?? 0 }));
      setEditorStep("editing");
      return;
    }
    const body = (await reply.json()) as RevisionRow;
    setLoadedRevision(body.revision ?? null);
    setDraftState(body.draftState ?? "DRAFT");
    const enqueued = body.enqueuedMigrationIds ?? [];
    let saved = fillPhrase(phrases.declarationsSavedNotice, {
      kind,
      key: selectedKey,
      revision: body.revision ?? "",
    });
    if (kind === "entity" && enqueued.length > 0) {
      saved =
        saved +
        " " +
        fillPhrase(phrases.declarationsMigrateAutoEnqueuedNotice, { count: String(enqueued.length) });
      setMigrateNotice(
        fillPhrase(phrases.declarationsMigrateAutoEnqueuedNotice, { count: String(enqueued.length) }),
      );
    }
    setSavedMessage(saved);
    setEditorStep("editing");
    await loadList();
    if (kind === "entity" && selectedKey) {
      await loadMigrations(selectedKey, "entity");
    }
  }

  function reviewPromote() {
    if (!canOfferDeclarationPromote({ canPromote, tenantId, selectedKey, loadedRevision })) {
      if (loadedRevision == null) setEditorProblem(phrases.declarationsPromoteNeedRevision);
      return;
    }
    setEditorProblem(null);
    setSavedMessage(null);
    setEditorStep("reviewing-promote");
  }

  async function confirmPromote() {
    if (!canOfferDeclarationPromote({ canPromote, tenantId, selectedKey, loadedRevision })) return;
    const tid = tenantId.trim();
    setEditorStep("promoting");
    setEditorProblem(null);
    const body = declarationPromoteRequestBody(loadedRevision);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey!)}/promote?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body ?? {}),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 409) {
      setEditorProblem(phrases.declarationsAlreadyPromoted);
      setEditorStep("editing");
      return;
    }
    if (reply?.status === 403) {
      setEditorProblem(phrases.declarationsPromoteForbidden);
      setEditorStep("editing");
      return;
    }
    if (!reply?.ok) {
      setEditorProblem(fillPhrase(phrases.declarationsPromoteFailed, { status: reply?.status ?? 0 }));
      setEditorStep("editing");
      return;
    }
    const doc = (await reply.json()) as {
      revision?: number;
      gitCommitSha?: string;
      draftState?: string;
    };
    setLoadedRevision(doc.revision ?? loadedRevision);
    setDraftState(doc.draftState ?? "PROMOTED");
    setSavedMessage(
      fillPhrase(phrases.declarationsPromotedNotice, {
        kind,
        key: selectedKey!,
        revision: doc.revision ?? loadedRevision ?? "",
        sha: doc.gitCommitSha ?? "—",
      }),
    );
    setEditorStep("editing");
    setPromoteHistory(null);
    setHistoryStatus("idle");
    await loadList();
  }

  async function loadPromoteHistoryForKey(key: string, kindOverride?: DeclarationKind) {
    const tid = tenantId.trim();
    if (!tid || !key) return;
    const activeKind = kindOverride ?? kind;
    setHistoryStatus("loading");
    setHistoryErrorStatus(0);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(activeKind)}/${encodeURIComponent(key)}/promotes?tenantId=${encodeURIComponent(tid)}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setHistoryErrorStatus(reply?.status ?? 0);
      setHistoryStatus("error");
      setPromoteHistory(null);
      return;
    }
    const doc = (await reply.json()) as { promotes?: PromoteHistoryRow[] };
    setPromoteHistory(doc.promotes ?? []);
    setHistoryStatus("ok");
  }

  async function loadPromoteHistory() {
    if (!selectedKey) return;
    await loadPromoteHistoryForKey(selectedKey);
  }

  async function loadEffective() {
    if (!selectedKey) return;
    const tid = tenantId.trim();
    if (!tid) {
      setEffectiveProblem(phrases.declarationsTenantRequired);
      return;
    }
    setEffectiveProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey)}/effective?tenantId=${encodeURIComponent(tid)}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setEffective(null);
      setEffectiveProblem(fillPhrase(phrases.declarationsLoadFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setEffective((await reply.json()) as EffectiveSummary);
  }

  function backToList() {
    setSelectedKey(null);
    setYamlBody("");
    setLoadedRevision(null);
    setDraftState(null);
    setEditorStep("editing");
    setEditorProblem(null);
    setSavedMessage(null);
    setBusinessTableNotice(null);
    setPromoteHistory(null);
    setHistoryStatus("idle");
    setMigrations(null);
    setMigrateStatus("idle");
    setMigrateErrorStatus(0);
    setMigrateSql("");
    setMigrateRevision("");
    setMigrateStep({ phase: "idle" });
    setMigrateProblem(null);
    setMigrateNotice(null);
    setShowManualMigrateEnqueue(false);
    setMigrateAudit(null);
    setMigrateAuditStatus("idle");
    setEffective(null);
    setEffectiveProblem(null);
    setComposerBlocks(emptyFlowPageBlocks());
    setEntityWizard(emptyEntityWizard());
    setFormWizard(emptyFormWizard());
    setComposerNotice(null);
  }

  async function loadMigrations(keyOverride?: string, kindOverride?: DeclarationKind) {
    const key = keyOverride ?? selectedKey;
    const activeKind = kindOverride ?? kind;
    if (!key || activeKind !== "entity") return;
    const tid = tenantId.trim();
    if (!tid) {
      setMigrateProblem(phrases.declarationsTenantRequired);
      return;
    }
    setMigrateStatus("loading");
    setMigrateErrorStatus(0);
    setMigrateProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(activeKind)}/${encodeURIComponent(key)}/migrations?tenantId=${encodeURIComponent(tid)}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setMigrateErrorStatus(reply?.status ?? 0);
      setMigrateStatus("error");
      setMigrations(null);
      return;
    }
    const doc = (await reply.json()) as { migrations?: MigrationRow[] };
    setMigrations(doc.migrations ?? []);
    setMigrateStatus("ok");
    await loadMigrateAudit(key);
  }

  async function loadMigrateAudit(keyOverride?: string) {
    const key = keyOverride ?? selectedKey;
    if (!key) return;
    setMigrateAuditStatus("loading");
    const reply = await fetch(`/api/platform/audit`, { cache: "no-store" }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 403) {
      setMigrateAudit(null);
      setMigrateAuditStatus("forbidden");
      return;
    }
    if (!reply?.ok) {
      setMigrateAudit(null);
      setMigrateAuditStatus("error");
      return;
    }
    const doc = (await reply.json()) as { entries?: MigrateAuditRow[] };
    setMigrateAudit(filterMigrateAuditEntries(doc.entries ?? [], key));
    setMigrateAuditStatus("ok");
  }


  function migrationById(migrationId: string): MigrationRow | undefined {
    return migrations?.find((m) => m.migrationId === migrationId);
  }

  function reviewEnqueueMigration() {
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) {
      setMigrateProblem(phrases.declarationsMigrateNeedKey);
      return;
    }
    const body = declarationMigrateEnqueueBody({
      revision: migrateRevision.trim() === "" ? loadedRevision : Number(migrateRevision),
      sqlText: migrateSql,
    });
    if (!body) {
      setMigrateProblem(phrases.declarationsMigrateSqlRequired);
      return;
    }
    if (migrateRevision.trim() !== "" && !Number.isFinite(Number(migrateRevision))) {
      setMigrateProblem(phrases.declarationsMigrateSqlRequired);
      return;
    }
    setMigrateProblem(null);
    setMigrateNotice(null);
    setMigrateStep({ phase: "review-enqueue" });
  }

  async function confirmEnqueueMigration() {
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    const body = declarationMigrateEnqueueBody({
      revision: migrateRevision.trim() === "" ? loadedRevision : Number(migrateRevision),
      sqlText: migrateSql,
    });
    if (!body) {
      setMigrateProblem(phrases.declarationsMigrateSqlRequired);
      setMigrateStep({ phase: "idle" });
      return;
    }
    const tid = tenantId.trim();
    setMigrateStep({ phase: "enqueueing" });
    setMigrateProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey!)}/migrations?tenantId=${encodeURIComponent(tid)}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 403) {
      setMigrateProblem(phrases.declarationsMigrateForbidden);
      setMigrateStep({ phase: "idle" });
      return;
    }
    if (!reply?.ok) {
      setMigrateProblem(fillPhrase(phrases.declarationsMigrateEnqueueFailed, { status: reply?.status ?? 0 }));
      setMigrateStep({ phase: "idle" });
      return;
    }
    const doc = (await reply.json()) as MigrationRow;
    setMigrateNotice(
      fillPhrase(phrases.declarationsMigrateEnqueuedNotice, {
        id: doc.migrationId ?? "—",
        revision: doc.declarationRevision ?? body.revision ?? loadedRevision ?? "",
      }),
    );
    setMigrateSql("");
    setMigrateStep({ phase: "idle" });
    await loadMigrations();
  }

  function reviewMarkReviewed(migrationId: string) {
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    setMigrateProblem(null);
    setMigrateNotice(null);
    setMigrateStep({ phase: "review-review", migrationId });
  }

  async function confirmMarkReviewed() {
    if (migrateStep.phase !== "review-review" && migrateStep.phase !== "reviewing") return;
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    const migrationId = migrateStep.migrationId;
    const tid = tenantId.trim();
    setMigrateStep({ phase: "reviewing", migrationId });
    setMigrateProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey!)}/migrations/${encodeURIComponent(migrationId)}/review?tenantId=${encodeURIComponent(tid)}`,
      { method: "POST", headers: { "content-type": "application/json" }, body: "{}" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 403) {
      setMigrateProblem(phrases.declarationsMigrateForbidden);
      setMigrateStep({ phase: "idle" });
      return;
    }
    if (!reply?.ok) {
      setMigrateProblem(fillPhrase(phrases.declarationsMigrateReviewFailed, { status: reply?.status ?? 0 }));
      setMigrateStep({ phase: "idle" });
      return;
    }
    setMigrateNotice(fillPhrase(phrases.declarationsMigrateReviewedNotice, { id: migrationId }));
    setMigrateStep({ phase: "idle" });
    await loadMigrations();
  }

  function reviewApplyMigration(migrationId: string) {
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    setMigrateProblem(null);
    setMigrateNotice(null);
    setMigrateStep({ phase: "review-apply", migrationId });
  }

  async function confirmApplyMigration() {
    if (migrateStep.phase !== "review-apply" && migrateStep.phase !== "applying") return;
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    const migrationId = migrateStep.migrationId;
    const tid = tenantId.trim();
    setMigrateStep({ phase: "applying", migrationId });
    setMigrateProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey!)}/migrations/${encodeURIComponent(migrationId)}/apply?tenantId=${encodeURIComponent(tid)}`,
      { method: "POST", headers: { "content-type": "application/json" }, body: "{}" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 403) {
      setMigrateProblem(phrases.declarationsMigrateForbidden);
      setMigrateStep({ phase: "idle" });
      return;
    }
    if (!reply?.ok) {
      const failedDoc = reply ? ((await reply.json().catch(() => null)) as MigrationRow | null) : null;
      const errBit = failedDoc?.errorMessage ? ` ${failedDoc.errorMessage}` : "";
      setMigrateProblem(
        fillPhrase(phrases.declarationsMigrateApplyFailed, { status: reply?.status ?? 0 }) + errBit,
      );
      setMigrateStep({ phase: "idle" });
      await loadMigrations();
      return;
    }
    setMigrateNotice(fillPhrase(phrases.declarationsMigrateAppliedNotice, { id: migrationId }));
    setMigrateStep({ phase: "idle" });
    await loadMigrations();
  }


  function reviewGuidedReviewApply(migrationId: string) {
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    setMigrateProblem(null);
    setMigrateNotice(null);
    setMigrateStep({ phase: "review-guided", migrationId });
  }

  async function confirmGuidedReviewApply() {
    if (migrateStep.phase !== "review-guided" && migrateStep.phase !== "guiding") return;
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    const migrationId = migrateStep.migrationId;
    const tid = tenantId.trim();
    setMigrateStep({ phase: "guiding", migrationId });
    setMigrateProblem(null);
    const base = `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey!)}/migrations/${encodeURIComponent(migrationId)}`;
    const reviewReply = await fetch(
      `${base}/review?tenantId=${encodeURIComponent(tid)}`,
      { method: "POST", headers: { "content-type": "application/json" }, body: "{}" },
    ).catch(() => null);
    if (reviewReply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reviewReply?.status === 403) {
      setMigrateProblem(phrases.declarationsMigrateForbidden);
      setMigrateStep({ phase: "idle" });
      return;
    }
    if (!reviewReply?.ok) {
      setMigrateProblem(
        fillPhrase(phrases.declarationsMigrateGuidedFailed, { status: reviewReply?.status ?? 0 }),
      );
      setMigrateStep({ phase: "idle" });
      await loadMigrations();
      return;
    }
    const applyReply = await fetch(
      `${base}/apply?tenantId=${encodeURIComponent(tid)}`,
      { method: "POST", headers: { "content-type": "application/json" }, body: "{}" },
    ).catch(() => null);
    if (applyReply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (applyReply?.status === 403) {
      setMigrateProblem(phrases.declarationsMigrateForbidden);
      setMigrateStep({ phase: "idle" });
      await loadMigrations();
      return;
    }
    if (!applyReply?.ok) {
      const failedDoc = applyReply ? ((await applyReply.json().catch(() => null)) as MigrationRow | null) : null;
      const errBit = failedDoc?.errorMessage ? ` ${failedDoc.errorMessage}` : "";
      setMigrateProblem(
        fillPhrase(phrases.declarationsMigrateGuidedFailed, { status: applyReply?.status ?? 0 }) + errBit,
      );
      setMigrateStep({ phase: "idle" });
      await loadMigrations();
      return;
    }
    setMigrateNotice(fillPhrase(phrases.declarationsMigrateGuidedAppliedNotice, { id: migrationId }));
    setMigrateStep({ phase: "idle" });
    await loadMigrations();
  }

  function reviewCancelMigration(migrationId: string) {
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    setMigrateProblem(null);
    setMigrateNotice(null);
    setMigrateStep({ phase: "review-cancel", migrationId });
  }

  async function confirmCancelMigration() {
    if (migrateStep.phase !== "review-cancel" && migrateStep.phase !== "cancelling") return;
    if (!canOfferDeclarationMigrate({ canMigrate, kind, tenantId, selectedKey })) return;
    const migrationId = migrateStep.migrationId;
    const tid = tenantId.trim();
    setMigrateStep({ phase: "cancelling", migrationId });
    setMigrateProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey!)}/migrations/${encodeURIComponent(migrationId)}/cancel?tenantId=${encodeURIComponent(tid)}`,
      { method: "POST", headers: { "content-type": "application/json" }, body: "{}" },
    ).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 403) {
      setMigrateProblem(phrases.declarationsMigrateForbidden);
      setMigrateStep({ phase: "idle" });
      return;
    }
    if (!reply?.ok) {
      setMigrateProblem(fillPhrase(phrases.declarationsMigrateCancelFailed, { status: reply?.status ?? 0 }));
      setMigrateStep({ phase: "idle" });
      return;
    }
    setMigrateNotice(fillPhrase(phrases.declarationsMigrateCancelledNotice, { id: migrationId }));
    setMigrateStep({ phase: "idle" });
    await loadMigrations();
  }

  function fillAdvancedFromFailed(row: MigrationRow) {
    setShowManualMigrateEnqueue(true);
    setMigrateSql(row.sqlText ?? "");
    setMigrateRevision(row.declarationRevision != null ? String(row.declarationRevision) : "");
    setMigrateNotice(phrases.declarationsMigrateFailedHint);
    setMigrateProblem(null);
  }

  const showPromote = canOfferDeclarationPromote({
    canPromote,
    tenantId,
    selectedKey,
    loadedRevision,
  });
  const showMigrate = canOfferDeclarationMigrate({
    canMigrate,
    kind,
    tenantId,
    selectedKey,
  });
  const busyStep = editorStep === "saving" || editorStep === "promoting";
  const migrateBusy =
    migrateStep.phase === "enqueueing" ||
    migrateStep.phase === "reviewing" ||
    migrateStep.phase === "applying" ||
    migrateStep.phase === "guiding" ||
    migrateStep.phase === "cancelling";

  const pathChips = useMemo(() => {
    if (kind !== "entity" || !selectedKey) return [];
    const promotedForRevision =
      (loadedRevision != null &&
        (promoteHistory?.some((p) => p.revision === loadedRevision) ?? false)) ||
      draftState === "PROMOTED";
    return migrationPathChecklist({
      hasDraft: loadedRevision != null || (yamlBody.trim() !== "" && selectedKey != null),
      revision: loadedRevision,
      migrations,
      promotedForRevision,
    });
  }, [kind, selectedKey, loadedRevision, promoteHistory, draftState, yamlBody, migrations]);

  function pathChipLabel(step: MigrationPathStep): string {
    switch (step) {
      case "draft":
        return phrases.declarationsMigratePathDraft;
      case "PENDING":
        return phrases.declarationsMigratePathPending;
      case "REVIEWED":
        return phrases.declarationsMigratePathReviewed;
      case "APPLIED":
        return phrases.declarationsMigratePathApplied;
      case "promote":
        return phrases.declarationsMigratePathPromote;
    }
  }

  function pathChipClass(state: MigrationPathChipState): string {
    switch (state) {
      case "done":
        return "border-green-600/40 bg-green-50 text-green-800";
      case "current":
        return "border-accent bg-accent/10 text-foreground font-medium";
      case "blocked":
        return "border-danger/50 bg-danger/5 text-danger";
      default:
        return "border-border bg-surface text-muted";
    }
  }

  const useTenantSelect = tenantOptions.length > 0;
  const selectOptions = useMemo(() => {
    if (!useTenantSelect) return tenantOptions;
    if (!tenantId.trim() || tenantOptions.some((t) => t.tenantId === tenantId)) return tenantOptions;
    return [{ tenantId, tenantName: tenantId }, ...tenantOptions];
  }, [useTenantSelect, tenantOptions, tenantId]);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end gap-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted">{phrases.declarationsTenantLabel}</span>
          {useTenantSelect ? (
            <select
              value={tenantId}
              onChange={(event) => {
                rememberTenant(event.target.value);
                backToList();
              }}
              className="min-w-[12rem] rounded-md border border-border bg-surface px-2 py-1"
              aria-label={phrases.declarationsTenantLabel}
            >
              <option value="">{phrases.declarationsTenantPlaceholder}</option>
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
              onBlur={() => backToList()}
              placeholder={phrases.declarationsTenantPlaceholder}
              className="min-w-[12rem] rounded-md border border-border bg-surface px-2 py-1 font-mono text-xs"
              aria-label={phrases.declarationsTenantLabel}
            />
          )}
        </label>
        {!useTenantSelect && tenantId ? (
          <button
            type="button"
            onClick={() => {
              rememberTenant(tenantId);
              backToList();
              void loadList();
            }}
            className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
          >
            {phrases.declarationsOpenAction}
          </button>
        ) : null}
        <div className="flex flex-col gap-1 text-sm">
          <span className="text-muted">{phrases.declarationsKindLabel}</span>
          <div className="flex gap-1" role="tablist" aria-label={phrases.declarationsKindLabel}>
            {DECLARATION_KINDS.map((k) => (
              <button
                key={k}
                type="button"
                role="tab"
                aria-selected={kind === k}
                onClick={() => {
                  setKind(k);
                  backToList();
                }}
                className={
                  kind === k
                    ? "rounded-md bg-accent px-2 py-1 text-xs text-white"
                    : "rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
                }
              >
                {kindLabel(phrases, k)}
              </button>
            ))}
          </div>
        </div>
      </div>

      {selectedKey ? (
        <div className="flex flex-col gap-3 rounded-lg border border-border bg-surface p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold">
              {fillPhrase(phrases.declarationsEditorTitle, { kind, key: selectedKey })}
              {loadedRevision != null ? (
                <span className="ml-2 font-normal text-muted">
                  r{loadedRevision}
                  {draftState ? ` · ${draftState}` : ""}
                </span>
              ) : null}
            </h2>
            <button
              type="button"
              onClick={backToList}
              className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
            >
              {phrases.declarationsBackToListAction}
            </button>
          </div>

          {editorStep === "reviewing" || editorStep === "saving" ? (
            <div className="rounded-md border border-border bg-background px-3 py-2 text-sm">
              <p className="font-medium">{phrases.declarationsReviewTitle}</p>
              <p className="mt-1 text-muted">
                {fillPhrase(phrases.declarationsReviewBody, {
                  tenant: tenantId.trim(),
                  kind,
                  key: selectedKey,
                })}
              </p>
            </div>
          ) : editorStep === "reviewing-promote" || editorStep === "promoting" ? (
            <div className="rounded-md border border-border bg-background px-3 py-2 text-sm">
              <p className="font-medium">{phrases.declarationsPromoteReviewTitle}</p>
              <p className="mt-1 text-muted">
                {fillPhrase(phrases.declarationsPromoteReviewBody, {
                  tenant: tenantId.trim(),
                  kind,
                  key: selectedKey,
                  revision: loadedRevision ?? "",
                })}
              </p>
            </div>
          ) : (
            <>
              {kind === "flow" ? (
                <FlowBlockComposer
                  phrases={phrases}
                  language={language}
                  value={composerBlocks}
                  disabled={!canWrite}
                  onChange={setComposerBlocks}
                  onApply={applyComposerToYaml}
                />
              ) : null}
              {kind === "entity" ? (
                <details className="rounded-md border border-dashed border-border bg-background/60 px-3 py-2">
                  <summary className="cursor-pointer text-xs font-medium text-muted">
                    {phrases.declarationsAdvancedEntityWizard}
                  </summary>
                  <div className="mt-2">
                    <EntityWizard
                      phrases={phrases}
                      value={entityWizard}
                      keyLocked={selectedKey != null}
                      disabled={!canWrite}
                      onChange={setEntityWizard}
                      onApply={applyEntityWizard}
                    />
                  </div>
                </details>
              ) : null}
              {kind === "form" ? (
                <details className="rounded-md border border-dashed border-border bg-background/60 px-3 py-2">
                  <summary className="cursor-pointer text-xs font-medium text-muted">
                    {phrases.declarationsAdvancedFormWizard}
                  </summary>
                  <div className="mt-2">
                    <FormWizard
                      phrases={phrases}
                      value={formWizard}
                      keyLocked={selectedKey != null}
                      disabled={!canWrite}
                      onChange={setFormWizard}
                      onApply={applyFormWizard}
                    />
                  </div>
                </details>
              ) : null}
              {composerNotice ? (
                <p role="status" className="text-xs text-green-700">
                  {composerNotice}
                </p>
              ) : null}
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted">{phrases.declarationsYamlLabel}</span>
                <textarea
                  value={yamlBody}
                  onChange={(event) => {
                    const next = event.target.value;
                    setYamlBody(next);
                    syncWizardsFromYaml(next, kind);
                  }}
                  rows={18}
                  disabled={!canWrite}
                  className="w-full rounded-md border border-border bg-background px-2 py-1 font-mono text-xs leading-5 disabled:opacity-70"
                  aria-label={phrases.declarationsYamlLabel}
                />
              </label>
            </>
          )}

          {editorProblem ? (
            <p role="alert" className="text-xs text-danger">
              {editorProblem}
            </p>
          ) : null}
          {savedMessage ? (
            <p role="status" className="text-xs text-green-700">
              {savedMessage}
            </p>
          ) : null}

          <div className="flex flex-wrap gap-2">
            {canWrite && editorStep === "editing" ? (
              <button
                type="button"
                onClick={reviewSave}
                className="rounded-md bg-accent px-2 py-1 text-xs text-white"
              >
                {phrases.declarationsSaveAction}
              </button>
            ) : null}
            {canWrite && (editorStep === "reviewing" || editorStep === "saving") ? (
              <button
                type="button"
                onClick={() => void confirmSave()}
                disabled={busyStep}
                className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
              >
                {editorStep === "saving"
                  ? phrases.declarationsSavingAction
                  : phrases.declarationsConfirmSaveAction}
              </button>
            ) : null}
            {showPromote && editorStep === "editing" ? (
              <button
                type="button"
                onClick={reviewPromote}
                className="rounded-md bg-accent px-2 py-1 text-xs text-white"
              >
                {phrases.declarationsPromoteAction}
              </button>
            ) : null}
            {canPromote && (editorStep === "reviewing-promote" || editorStep === "promoting") ? (
              <button
                type="button"
                onClick={() => void confirmPromote()}
                disabled={busyStep}
                className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
              >
                {editorStep === "promoting"
                  ? phrases.declarationsPromotingAction
                  : phrases.declarationsConfirmPromoteAction}
              </button>
            ) : null}
            {editorStep !== "editing" ? (
              <button
                type="button"
                onClick={() => setEditorStep("editing")}
                disabled={busyStep}
                className="rounded-md border border-border px-2 py-1 text-xs"
              >
                {phrases.cancelAction}
              </button>
            ) : null}
            {editorStep === "editing" ? (
              <button
                type="button"
                onClick={() => void loadPromoteHistory()}
                className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
              >
                {phrases.declarationsPromoteHistoryAction}
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => void loadEffective()}
              disabled={busyStep}
              className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background disabled:opacity-60"
            >
              {phrases.declarationsEffectiveAction}
            </button>
          </div>

          {historyStatus === "loading" ? (
            <p className="text-xs text-muted">{phrases.savingAction}</p>
          ) : null}
          {historyStatus === "error" ? (
            <p role="alert" className="text-xs text-danger">
              {fillPhrase(phrases.declarationsLoadFailed, { status: historyErrorStatus })}
            </p>
          ) : null}
          {historyStatus === "ok" && promoteHistory ? (
            <div className="rounded-md border border-border bg-background px-3 py-2 text-xs">
              <p className="font-medium">{phrases.declarationsPromoteHistoryTitle}</p>
              {promoteHistory.length === 0 ? (
                <p className="mt-1 text-muted">{phrases.declarationsPromoteHistoryEmpty}</p>
              ) : (
                <table className="mt-2 w-full border-collapse text-left">
                  <thead className="text-muted">
                    <tr>
                      <th className="py-1 pr-2 font-medium">{phrases.declarationsPromoteHistoryRevision}</th>
                      <th className="py-1 pr-2 font-medium">{phrases.declarationsPromoteHistorySha}</th>
                      <th className="py-1 pr-2 font-medium">{phrases.declarationsPromoteHistoryAt}</th>
                      <th className="py-1 font-medium">{phrases.declarationsPromoteHistoryBy}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {promoteHistory.map((row) => (
                      <tr key={`${row.revision}-${row.gitCommitSha}`} className="border-t border-border align-top">
                        <td className="py-1 pr-2">{row.revision ?? "—"}</td>
                        <td className="py-1 pr-2 font-mono">{row.gitCommitSha ?? "—"}</td>
                        <td className="py-1 pr-2 text-muted">{row.promotedAt ?? "—"}</td>
                        <td className="py-1 font-mono">{row.promotedBySubjectId ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ) : null}

          {kind !== "entity" ? (
            <p role="note" className="text-xs text-muted">
              {phrases.declarationsMigrateEntityOnlyNote}
            </p>
          ) : (
            <div className="rounded-md border border-border bg-background px-3 py-2 text-xs">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="font-medium">{phrases.declarationsMigrateTitle}</p>
                  <p className="mt-0.5 text-muted">{phrases.declarationsMigrateHint}</p>
                </div>
                <button
                  type="button"
                  onClick={() => void loadMigrations()}
                  disabled={migrateBusy || busyStep}
                  className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-surface disabled:opacity-60"
                >
                  {phrases.declarationsMigrateLoadAction}
                </button>
              </div>

              {pathChips.length > 0 ? (
                <div className="mt-2">
                  <p className="text-[11px] font-medium text-muted">{phrases.declarationsMigratePathTitle}</p>
                  <ol className="mt-1 flex flex-wrap items-center gap-1">
                    {pathChips.map((chip, index) => (
                      <li key={chip.step} className="flex items-center gap-1">
                        {index > 0 ? <span className="text-muted">→</span> : null}
                        <span
                          className={`rounded-full border px-2 py-0.5 text-[11px] ${pathChipClass(chip.state)}`}
                          title={chip.state}
                        >
                          {pathChipLabel(chip.step)}
                        </span>
                      </li>
                    ))}
                  </ol>
                  {pathChips.some((c) => c.state === "blocked") ? (
                    <p className="mt-1 text-[11px] text-danger">{phrases.declarationsMigratePathBlockedHint}</p>
                  ) : null}
                </div>
              ) : null}

              <div className="mt-2 rounded-md border border-border bg-surface px-2 py-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <p className="font-medium">{phrases.declarationsMigrateAuditTitle}</p>
                    <p className="mt-0.5 text-[11px] text-muted">{phrases.declarationsMigrateAuditHint}</p>
                  </div>
                  <div className="flex flex-wrap gap-1">
                    <button
                      type="button"
                      onClick={() => void loadMigrateAudit()}
                      disabled={migrateBusy || busyStep || migrateAuditStatus === "loading"}
                      className="rounded-md border border-border px-2 py-0.5 text-[11px] hover:bg-background disabled:opacity-60"
                    >
                      {phrases.declarationsMigrateAuditRefresh}
                    </button>
                    <a
                      href="/audit"
                      className="rounded-md border border-border px-2 py-0.5 text-[11px] hover:bg-background"
                    >
                      {phrases.declarationsMigrateAuditLink}
                    </a>
                  </div>
                </div>
                {migrateAuditStatus === "loading" ? (
                  <p className="mt-1 text-muted">{phrases.savingAction}</p>
                ) : null}
                {migrateAuditStatus === "forbidden" ? (
                  <p role="status" className="mt-1 text-muted">
                    {phrases.declarationsMigrateAuditForbidden}
                  </p>
                ) : null}
                {migrateAuditStatus === "error" ? (
                  <p role="alert" className="mt-1 text-danger">
                    {fillPhrase(phrases.declarationsLoadFailed, { status: 0 })}
                  </p>
                ) : null}
                {migrateAuditStatus === "ok" && migrateAudit ? (
                  migrateAudit.length === 0 ? (
                    <p className="mt-1 text-muted">{phrases.declarationsMigrateAuditEmpty}</p>
                  ) : (
                    <table className="mt-2 w-full border-collapse text-left text-[11px]">
                      <thead className="text-muted">
                        <tr>
                          <th className="py-0.5 pr-2 font-medium">{phrases.declarationsMigrateAuditTimeColumn}</th>
                          <th className="py-0.5 pr-2 font-medium">{phrases.declarationsMigrateAuditActorColumn}</th>
                          <th className="py-0.5 pr-2 font-medium">{phrases.declarationsMigrateAuditActionColumn}</th>
                          <th className="py-0.5 pr-2 font-medium">{phrases.declarationsMigrateAuditTargetColumn}</th>
                          <th className="py-0.5 font-medium">{phrases.declarationsMigrateAuditOutcomeColumn}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {migrateAudit.slice(0, 12).map((row) => (
                          <tr key={row.auditEntryId ?? `${row.occurredAt}-${row.actionName}`} className="border-t border-border align-top">
                            <td className="whitespace-nowrap py-0.5 pr-2">{row.occurredAt ?? "—"}</td>
                            <td className="py-0.5 pr-2">{row.actorLogin ?? row.actor ?? "—"}</td>
                            <td className="py-0.5 pr-2" title={row.actionName}>
                              {language === "zh" ? (row.actionWordZh ?? row.actionName) : (row.actionWordEn ?? row.actionName)}
                            </td>
                            <td className="max-w-[12rem] py-0.5 pr-2 font-mono break-all">{row.actionTarget ?? "—"}</td>
                            <td className="py-0.5">
                              {language === "zh" ? (row.outcomeWordZh ?? row.outcome) : (row.outcomeWordEn ?? row.outcome)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )
                ) : null}
              </div>

              {migrateStep.phase === "review-enqueue" || migrateStep.phase === "enqueueing" ? (
                <div className="mt-2 rounded-md border border-border bg-surface px-2 py-2">
                  <p className="font-medium">{phrases.declarationsMigrateEnqueueReviewTitle}</p>
                  <p className="mt-1 text-muted">
                    {fillPhrase(phrases.declarationsMigrateEnqueueReviewBody, {
                      tenant: tenantId.trim(),
                      key: selectedKey ?? "",
                      revision:
                        migrateRevision.trim() ||
                        (loadedRevision != null ? String(loadedRevision) : "latest"),
                    })}
                  </p>
                  <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap font-mono text-[11px]">
                    {migrateSql.trim()}
                  </pre>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => void confirmEnqueueMigration()}
                      disabled={migrateBusy}
                      className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
                    >
                      {migrateStep.phase === "enqueueing"
                        ? phrases.declarationsMigrateEnqueueingAction
                        : phrases.declarationsMigrateConfirmEnqueueAction}
                    </button>
                    <button
                      type="button"
                      onClick={() => setMigrateStep({ phase: "idle" })}
                      disabled={migrateBusy}
                      className="rounded-md border border-border px-2 py-1 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  </div>
                </div>
              ) : migrateStep.phase === "review-review" || migrateStep.phase === "reviewing" ? (
                <div className="mt-2 rounded-md border border-border bg-surface px-2 py-2">
                  <p className="font-medium">{phrases.declarationsMigrateReviewTitle}</p>
                  <p className="mt-1 text-muted">
                    {fillPhrase(phrases.declarationsMigrateReviewBody, { id: migrateStep.migrationId })}
                  </p>
                  <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap font-mono text-[11px]">
                    {migrationById(migrateStep.migrationId)?.sqlText?.trim() || "—"}
                  </pre>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => void confirmMarkReviewed()}
                      disabled={migrateBusy}
                      className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
                    >
                      {migrateStep.phase === "reviewing"
                        ? phrases.declarationsMigrateReviewingAction
                        : phrases.declarationsMigrateConfirmReviewAction}
                    </button>
                    <button
                      type="button"
                      onClick={() => setMigrateStep({ phase: "idle" })}
                      disabled={migrateBusy}
                      className="rounded-md border border-border px-2 py-1 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  </div>
                </div>
              ) : migrateStep.phase === "review-apply" || migrateStep.phase === "applying" ? (
                <div className="mt-2 rounded-md border border-border bg-surface px-2 py-2">
                  <p className="font-medium">{phrases.declarationsMigrateApplyReviewTitle}</p>
                  <p className="mt-1 text-muted">
                    {fillPhrase(phrases.declarationsMigrateApplyReviewBody, { id: migrateStep.migrationId })}
                  </p>
                  <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap font-mono text-[11px]">
                    {migrationById(migrateStep.migrationId)?.sqlText?.trim() || "—"}
                  </pre>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => void confirmApplyMigration()}
                      disabled={migrateBusy}
                      className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
                    >
                      {migrateStep.phase === "applying"
                        ? phrases.declarationsMigrateApplyingAction
                        : phrases.declarationsMigrateConfirmApplyAction}
                    </button>
                    <button
                      type="button"
                      onClick={() => setMigrateStep({ phase: "idle" })}
                      disabled={migrateBusy}
                      className="rounded-md border border-border px-2 py-1 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  </div>
                </div>
              ) : migrateStep.phase === "review-guided" || migrateStep.phase === "guiding" ? (
                <div className="mt-2 rounded-md border border-accent bg-surface px-2 py-2">
                  <p className="font-medium">{phrases.declarationsMigrateGuidedReviewTitle}</p>
                  <p className="mt-1 text-muted">
                    {fillPhrase(phrases.declarationsMigrateGuidedReviewBody, { id: migrateStep.migrationId })}
                  </p>
                  <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap font-mono text-[11px]">
                    {migrationById(migrateStep.migrationId)?.sqlText?.trim() || "—"}
                  </pre>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => void confirmGuidedReviewApply()}
                      disabled={migrateBusy}
                      className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
                    >
                      {migrateStep.phase === "guiding"
                        ? phrases.declarationsMigrateGuidingAction
                        : phrases.declarationsMigrateConfirmGuidedAction}
                    </button>
                    <button
                      type="button"
                      onClick={() => setMigrateStep({ phase: "idle" })}
                      disabled={migrateBusy}
                      className="rounded-md border border-border px-2 py-1 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  </div>
                </div>
              ) : migrateStep.phase === "review-cancel" || migrateStep.phase === "cancelling" ? (
                <div className="mt-2 rounded-md border border-border bg-surface px-2 py-2">
                  <p className="font-medium">{phrases.declarationsMigrateCancelReviewTitle}</p>
                  <p className="mt-1 text-muted">
                    {fillPhrase(phrases.declarationsMigrateCancelReviewBody, { id: migrateStep.migrationId })}
                  </p>
                  <pre className="mt-2 max-h-24 overflow-auto whitespace-pre-wrap font-mono text-[11px]">
                    {migrationById(migrateStep.migrationId)?.sqlText?.trim() || "—"}
                  </pre>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => void confirmCancelMigration()}
                      disabled={migrateBusy}
                      className="rounded-md border border-danger px-2 py-1 text-xs text-danger disabled:opacity-60"
                    >
                      {migrateStep.phase === "cancelling"
                        ? phrases.declarationsMigrateCancellingAction
                        : phrases.declarationsMigrateConfirmCancelAction}
                    </button>
                    <button
                      type="button"
                      onClick={() => setMigrateStep({ phase: "idle" })}
                      disabled={migrateBusy}
                      className="rounded-md border border-border px-2 py-1 text-xs"
                    >
                      {phrases.cancelAction}
                    </button>
                  </div>
                </div>
              ) : showMigrate ? (
                <div className="mt-3 rounded-md border border-border bg-surface px-2 py-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="font-medium">{phrases.declarationsMigrateAdvancedManualTitle}</p>
                    <button
                      type="button"
                      onClick={() => setShowManualMigrateEnqueue((v) => !v)}
                      className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
                    >
                      {showManualMigrateEnqueue
                        ? phrases.declarationsMigrateAdvancedHideAction
                        : phrases.declarationsMigrateAdvancedShowAction}
                    </button>
                  </div>
                  {showManualMigrateEnqueue ? (
                    <>
                      <p className="mt-2 font-medium">{phrases.declarationsMigrateEnqueueTitle}</p>
                      <div className="mt-2 flex flex-wrap gap-3">
                        <label className="flex flex-col gap-1">
                          <span className="text-muted">{phrases.declarationsMigrateRevisionLabel}</span>
                          <input
                            value={migrateRevision}
                            onChange={(event) => setMigrateRevision(event.target.value)}
                            className="w-24 rounded-md border border-border bg-background px-2 py-1 font-mono"
                            aria-label={phrases.declarationsMigrateRevisionLabel}
                          />
                        </label>
                        <label className="flex min-w-[16rem] flex-1 flex-col gap-1">
                          <span className="text-muted">{phrases.declarationsMigrateSqlLabel}</span>
                          <textarea
                            value={migrateSql}
                            onChange={(event) => setMigrateSql(event.target.value)}
                            rows={3}
                            className="w-full rounded-md border border-border bg-background px-2 py-1 font-mono leading-5"
                            aria-label={phrases.declarationsMigrateSqlLabel}
                          />
                        </label>
                      </div>
                      <button
                        type="button"
                        onClick={reviewEnqueueMigration}
                        className="mt-2 rounded-md bg-accent px-2 py-1 text-xs text-white"
                      >
                        {phrases.declarationsMigrateEnqueueAction}
                      </button>
                    </>
                  ) : null}
                </div>
              ) : null}

              {migrateProblem ? (
                <p role="alert" className="mt-2 text-danger">
                  {migrateProblem}
                </p>
              ) : null}
              {migrateNotice ? (
                <p role="status" className="mt-2 text-green-700">
                  {migrateNotice}
                </p>
              ) : null}

              {migrateStatus === "loading" ? (
                <p className="mt-2 text-muted">{phrases.savingAction}</p>
              ) : null}
              {migrateStatus === "error" ? (
                <p role="alert" className="mt-2 text-danger">
                  {fillPhrase(phrases.declarationsLoadFailed, { status: migrateErrorStatus })}
                </p>
              ) : null}
              {migrateStatus === "ok" && migrations ? (
                migrations.length === 0 ? (
                  <p className="mt-2 text-muted">{phrases.declarationsMigrateEmpty}</p>
                ) : (
                  <table className="mt-2 w-full border-collapse text-left">
                    <thead className="text-muted">
                      <tr>
                        <th className="py-1 pr-2 font-medium">{phrases.declarationsMigrateIdColumn}</th>
                        <th className="py-1 pr-2 font-medium">{phrases.declarationsMigrateRevisionColumn}</th>
                        <th className="py-1 pr-2 font-medium">{phrases.declarationsMigrateStatusColumn}</th>
                        <th className="py-1 pr-2 font-medium">{phrases.declarationsMigrateSqlColumn}</th>
                        <th className="py-1 pr-2 font-medium">{phrases.declarationsMigrateErrorColumn}</th>
                        <th className="py-1 font-medium" />
                      </tr>
                    </thead>
                    <tbody>
                      {migrations.map((row) => (
                        <tr
                          key={row.migrationId ?? `${row.declarationRevision}-${row.createdAt}`}
                          className={
                            isFailedMigration(row.status)
                              ? "border-t border-danger/40 bg-danger/5 align-top"
                              : "border-t border-border align-top"
                          }
                        >
                          <td className="py-1 pr-2 font-mono">{row.migrationId ?? "—"}</td>
                          <td className="py-1 pr-2">{row.declarationRevision ?? "—"}</td>
                          <td
                            className={
                              isFailedMigration(row.status)
                                ? "py-1 pr-2 font-medium text-danger"
                                : "py-1 pr-2"
                            }
                          >
                            {row.status ?? "—"}
                          </td>
                          <td className="max-w-[14rem] py-1 pr-2 font-mono whitespace-pre-wrap break-all">
                            {row.sqlText ?? "—"}
                          </td>
                          <td className="max-w-[10rem] py-1 pr-2 text-danger whitespace-pre-wrap break-all">
                            {isFailedMigration(row.status) ? (
                              <>
                                <span className="font-medium">{row.errorMessage ?? "—"}</span>
                                <p className="mt-1 text-[11px] font-normal text-muted">
                                  {phrases.declarationsMigrateFailedHint}
                                </p>
                              </>
                            ) : (
                              (row.errorMessage ?? "—")
                            )}
                          </td>
                          <td className="py-1">
                            <div className="flex flex-wrap gap-1">
                              {showMigrate &&
                              canGuidedReviewApplyMigration(row.status) &&
                              migrateStep.phase === "idle" ? (
                                <button
                                  type="button"
                                  onClick={() => reviewGuidedReviewApply(row.migrationId!)}
                                  className="rounded-md bg-accent px-1.5 py-0.5 text-white hover:opacity-90"
                                >
                                  {phrases.declarationsMigrateGuidedAction}
                                </button>
                              ) : null}
                              {showMigrate && canReviewMigration(row.status) && migrateStep.phase === "idle" ? (
                                <button
                                  type="button"
                                  onClick={() => reviewMarkReviewed(row.migrationId!)}
                                  className="rounded-md border border-border px-1.5 py-0.5 hover:bg-surface"
                                >
                                  {phrases.declarationsMigrateReviewAction}
                                </button>
                              ) : null}
                              {showMigrate && canApplyMigration(row.status) && migrateStep.phase === "idle" ? (
                                <button
                                  type="button"
                                  onClick={() => reviewApplyMigration(row.migrationId!)}
                                  className="rounded-md border border-border px-1.5 py-0.5 hover:bg-surface"
                                >
                                  {phrases.declarationsMigrateApplyAction}
                                </button>
                              ) : null}
                              {showMigrate && canCancelMigration(row.status) && migrateStep.phase === "idle" ? (
                                <button
                                  type="button"
                                  onClick={() => reviewCancelMigration(row.migrationId!)}
                                  className="rounded-md border border-border px-1.5 py-0.5 text-muted hover:bg-surface"
                                >
                                  {phrases.declarationsMigrateCancelAction}
                                </button>
                              ) : null}
                              {showMigrate && isFailedMigration(row.status) && migrateStep.phase === "idle" ? (
                                <button
                                  type="button"
                                  onClick={() => fillAdvancedFromFailed(row)}
                                  className="rounded-md border border-danger/50 px-1.5 py-0.5 text-danger hover:bg-surface"
                                >
                                  {phrases.declarationsMigrateRequeueAction}
                                </button>
                              ) : null}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )
              ) : null}
            </div>
          )}

          {effectiveProblem ? (
            <p role="alert" className="text-xs text-danger">
              {effectiveProblem}
            </p>
          ) : null}
          {effective ? (
            <div className="rounded-md border border-border bg-background px-3 py-2 text-xs">
              <p className="font-medium">{phrases.declarationsEffectiveTitle}</p>
              <p className="mt-1 text-muted">
                {effective.fromDraft
                  ? phrases.declarationsEffectiveFromDraft
                  : phrases.declarationsEffectiveFromClasspath}
              </p>
              <p className="mt-1">
                {fillPhrase(phrases.declarationsEffectiveVersion, {
                  version: effective.version ?? "—",
                })}
              </p>
              <p className="mt-1">
                {effective.fieldNames && effective.fieldNames.length > 0
                  ? fillPhrase(phrases.declarationsEffectiveFields, {
                      fields: effective.fieldNames.join(", "),
                    })
                  : phrases.declarationsEffectiveNone}
              </p>
            </div>
          ) : null}
        </div>
      ) : (
        <>
          {canWrite ? (
            <BusinessTableWizard
              phrases={phrases}
              disabled={!canWrite}
              tenantId={tenantId}
              onConfirm={createBusinessTableDrafts}
            />
          ) : null}
          <p className="text-xs text-muted">{phrases.declarationsBusinessTablePathHint}</p>
          {businessTableNotice ? (
            <p role="status" className="text-xs text-green-700">
              {businessTableNotice}
            </p>
          ) : null}
          {!tenantId.trim() ? (
            <p className="text-sm text-muted">{phrases.declarationsEmptyTenant}</p>
          ) : listStatus === "loading" ? (
            <p className="text-sm text-muted">{phrases.savingAction}</p>
          ) : listStatus === "error" ? (
            <p role="alert" className="text-sm text-danger">
              {fillPhrase(phrases.declarationsLoadFailed, { status: listErrorStatus })}
            </p>
          ) : (
            <div>
              <h2 className="mb-2 text-sm font-semibold">{phrases.declarationsListTitle}</h2>
              {!listRows.some((r) => r.hasDraft) ? (
                <p className="mb-2 text-sm text-muted">{phrases.declarationsEmptyPointToWizard}</p>
              ) : null}
              {listRows.length === 0 ? (
                <p className="text-sm text-muted">{phrases.declarationsNoRows}</p>
              ) : (
                <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
                  <thead className="bg-background text-left text-muted">
                    <tr>
                      <th className="px-3 py-2 font-medium">{phrases.declarationsKeyColumn}</th>
                      <th className="px-3 py-2 font-medium">{phrases.declarationsRevisionColumn}</th>
                      <th className="px-3 py-2 font-medium">{phrases.declarationsStateColumn}</th>
                      <th className="px-3 py-2 font-medium">{phrases.declarationsUpdatedColumn}</th>
                      <th className="px-3 py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {listRows.map((row) => {
                      const draft = draftByKey.get(row.key);
                      return (
                        <tr key={row.key} className="border-t border-border align-top">
                          <td className="px-3 py-2 font-mono text-xs">
                            <span className="inline-flex flex-wrap items-center gap-1">
                              {row.key}
                              {!row.hasDraft && isClasspathSampleKey(kind, row.key) ? (
                                <span
                                  title={phrases.declarationsSampleHint}
                                  className="rounded bg-background px-1 py-0.5 text-[10px] font-sans text-muted"
                                >
                                  {phrases.declarationsSampleBadge}
                                </span>
                              ) : null}
                            </span>
                          </td>
                          <td className="px-3 py-2 text-xs">
                            {row.hasDraft ? (draft?.revision ?? "—") : "—"}
                          </td>
                          <td className="px-3 py-2 text-xs">
                            {row.hasDraft
                              ? (draft?.draftState ?? "DRAFT")
                              : phrases.declarationsClasspathHint}
                          </td>
                          <td className="px-3 py-2 text-xs text-muted">
                            {row.hasDraft ? (draft?.updatedAt ?? "—") : "—"}
                          </td>
                          <td className="px-3 py-2">
                            <button
                              type="button"
                              onClick={() => void openKey(row.key)}
                              className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
                            >
                              {phrases.declarationsOpenAction}
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>
          )}

          {canWrite && tenantId.trim() ? (
            <details className="rounded-lg border border-dashed border-border bg-surface/80 p-4">
              <summary className="cursor-pointer text-sm font-medium text-muted">
                {phrases.declarationsAdvancedSingleKindTitle}{" "}
                <span className="font-normal">({kind})</span>
              </summary>
              <p className="mt-2 text-xs text-muted">{phrases.declarationsAdvancedSingleKindHint}</p>
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted">{phrases.declarationsNewKeyLabel}</span>
                  <input
                    value={newKey}
                    onChange={(event) => setNewKey(event.target.value)}
                    className="rounded-md border border-border bg-background px-2 py-1 font-mono text-xs"
                    aria-label={phrases.declarationsNewKeyLabel}
                  />
                </label>
                <button
                  type="button"
                  onClick={startNewDraft}
                  className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background"
                >
                  {phrases.declarationsNewAction}
                </button>
              </div>
              {editorProblem && !selectedKey ? (
                <p role="alert" className="mt-2 text-xs text-danger">
                  {editorProblem}
                </p>
              ) : null}
            </details>
          ) : null}
        </>
      )}
    </div>
  );
}
