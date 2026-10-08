"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { fillPhrase, type LanguageCode, type PhraseBook } from "@/i18n/phrases";
import {
  DECLARATION_KINDS,
  canApplyMigration,
  canOfferDeclarationMigrate,
  canOfferDeclarationPromote,
  canReviewMigration,
  declarationMigrateEnqueueBody,
  declarationPromoteRequestBody,
  declarationTenantCookieWrite,
  mergeDraftAndClasspathKeys,
  templateYamlFor,
  type DeclarationKind,
} from "@/lib/declaration-draft";
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

type MigrateStep =
  | { phase: "idle" }
  | { phase: "review-enqueue" }
  | { phase: "enqueueing" }
  | { phase: "review-review"; migrationId: string }
  | { phase: "reviewing"; migrationId: string }
  | { phase: "review-apply"; migrationId: string }
  | { phase: "applying"; migrationId: string };

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

  async function openKey(key: string, seedYaml?: string) {
    const tid = tenantId.trim();
    if (!tid) {
      setEditorProblem(phrases.declarationsTenantRequired);
      return;
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
    setEffective(null);
    setEffectiveProblem(null);

    const existing = draftByKey.get(key);
    if (existing?.yamlBody != null) {
      setYamlBody(existing.yamlBody);
      syncWizardsFromYaml(existing.yamlBody, kind);
      setLoadedRevision(existing.revision ?? null);
      setDraftState(existing.draftState ?? null);
      return;
    }

    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(key)}?tenantId=${encodeURIComponent(tid)}`,
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
      syncWizardsFromYaml(yaml, kind);
      setLoadedRevision(body.revision ?? null);
      setDraftState(body.draftState ?? null);
      return;
    }
    if (reply?.status === 404) {
      const yaml = seedYaml ?? templateYamlFor(kind, key);
      setYamlBody(yaml);
      syncWizardsFromYaml(yaml, kind);
      setLoadedRevision(null);
      setDraftState(null);
      return;
    }
    const yaml = seedYaml ?? templateYamlFor(kind, key);
    setYamlBody(yaml);
    syncWizardsFromYaml(yaml, kind);
    setLoadedRevision(null);
    setDraftState(null);
    if (reply && reply.status !== 404) {
      setEditorProblem(fillPhrase(phrases.declarationsLoadFailed, { status: reply.status }));
    }
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
    setSavedMessage(
      fillPhrase(phrases.declarationsSavedNotice, {
        kind,
        key: selectedKey,
        revision: body.revision ?? "",
      }),
    );
    setEditorStep("editing");
    await loadList();
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

  async function loadPromoteHistory() {
    if (!selectedKey) return;
    const tid = tenantId.trim();
    if (!tid) {
      setEditorProblem(phrases.declarationsTenantRequired);
      return;
    }
    setHistoryStatus("loading");
    setHistoryErrorStatus(0);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey)}/promotes?tenantId=${encodeURIComponent(tid)}`,
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
    setEffective(null);
    setEffectiveProblem(null);
    setComposerBlocks(emptyFlowPageBlocks());
    setEntityWizard(emptyEntityWizard());
    setFormWizard(emptyFormWizard());
    setComposerNotice(null);
  }

  async function loadMigrations() {
    if (!selectedKey || kind !== "entity") return;
    const tid = tenantId.trim();
    if (!tid) {
      setMigrateProblem(phrases.declarationsTenantRequired);
      return;
    }
    setMigrateStatus("loading");
    setMigrateErrorStatus(0);
    setMigrateProblem(null);
    const reply = await fetch(
      `/api/platform/declarations/${encodeURIComponent(kind)}/${encodeURIComponent(selectedKey)}/migrations?tenantId=${encodeURIComponent(tid)}`,
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
    migrateStep.phase === "applying";

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
                <EntityWizard
                  phrases={phrases}
                  value={entityWizard}
                  keyLocked={selectedKey != null}
                  disabled={!canWrite}
                  onChange={setEntityWizard}
                  onApply={applyEntityWizard}
                />
              ) : null}
              {kind === "form" ? (
                <FormWizard
                  phrases={phrases}
                  value={formWizard}
                  keyLocked={selectedKey != null}
                  disabled={!canWrite}
                  onChange={setFormWizard}
                  onApply={applyFormWizard}
                />
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
              ) : showMigrate ? (
                <div className="mt-3 rounded-md border border-border bg-surface px-2 py-2">
                  <p className="font-medium">{phrases.declarationsMigrateEnqueueTitle}</p>
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
                        <tr key={row.migrationId ?? `${row.declarationRevision}-${row.createdAt}`} className="border-t border-border align-top">
                          <td className="py-1 pr-2 font-mono">{row.migrationId ?? "—"}</td>
                          <td className="py-1 pr-2">{row.declarationRevision ?? "—"}</td>
                          <td className="py-1 pr-2">{row.status ?? "—"}</td>
                          <td className="max-w-[14rem] py-1 pr-2 font-mono whitespace-pre-wrap break-all">
                            {row.sqlText ?? "—"}
                          </td>
                          <td className="max-w-[10rem] py-1 pr-2 text-danger whitespace-pre-wrap break-all">
                            {row.errorMessage ?? "—"}
                          </td>
                          <td className="py-1">
                            <div className="flex flex-wrap gap-1">
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
                          <td className="px-3 py-2 font-mono text-xs">{row.key}</td>
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
            <div className="rounded-lg border border-border bg-surface p-4">
              <h2 className="mb-2 text-sm font-semibold">{phrases.declarationsNewTitle}</h2>
              <div className="flex flex-wrap items-end gap-2">
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
                  className="rounded-md bg-accent px-2 py-1 text-xs text-white"
                >
                  {phrases.declarationsNewAction}
                </button>
              </div>
              {editorProblem && !selectedKey ? (
                <p role="alert" className="mt-2 text-xs text-danger">
                  {editorProblem}
                </p>
              ) : null}
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
