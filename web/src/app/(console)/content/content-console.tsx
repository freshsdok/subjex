"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import type { PhraseBook } from "@/i18n/phrases";

type Block = {
  blockId?: string | null;
  type: "TITLE" | "TEXT" | "IMAGE" | "LINK" | string;
  text?: string | null;
  format?: "PLAIN" | "MARKDOWN" | string | null;
  objectId?: string | null;
  href?: string | null;
  alt?: string | null;
};

type Summary = {
  pageId?: string;
  slug?: string;
  title?: string;
  status?: string;
  updatedAt?: string;
  publishedAt?: string | null;
};

type Document = Summary & { blocks?: Block[] };

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  canWrite: boolean;
};

/**
 * Thin operator content-page console — list, create, edit, publish, unpublish.
 * 薄操作员内容页台：列出、创建、编辑、发布、取消发布。
 */
export function ContentConsole({ phrases, initialTenantId, canWrite }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [rows, setRows] = useState<Summary[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [draft, setDraft] = useState<Document>({ slug: "", title: "", status: "DRAFT", blocks: [] });
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const tenantQ = () => `tenantId=${encodeURIComponent(tenantId.trim())}`;

  const loadList = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setRows([]);
      setStatus("idle");
      return;
    }
    setStatus("loading");
    const reply = await fetch(`/api/platform/content-pages?${tenantQ()}`, { cache: "no-store" }).catch(
      () => null,
    );
    if (!reply?.ok) {
      setRows([]);
      setStatus("error");
      return;
    }
    const body = (await reply.json()) as Summary[];
    setRows(Array.isArray(body) ? body : []);
    setStatus("ok");
  }, [tenantId]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  async function onRefresh(event: FormEvent) {
    event.preventDefault();
    setNotice(null);
    await loadList();
  }

  async function openPage(pageId: string) {
    const tid = tenantId.trim();
    if (!tid || !pageId) return;
    setNotice(null);
    const reply = await fetch(
      `/api/platform/content-pages/${encodeURIComponent(pageId)}?${tenantQ()}`,
      { cache: "no-store" },
    ).catch(() => null);
    if (!reply?.ok) {
      setNotice(phrases.contentLoadFailed);
      return;
    }
    const body = (await reply.json()) as Document;
    setSelectedId(pageId);
    setDraft({
      pageId,
      slug: body.slug ?? "",
      title: body.title ?? "",
      status: body.status ?? "DRAFT",
      blocks: body.blocks ?? [],
    });
  }

  function newDraft() {
    setSelectedId("");
    setDraft({ slug: "", title: "", status: "DRAFT", blocks: [] });
    setNotice(null);
  }

  function patchBlock(index: number, patch: Partial<Block>) {
    setDraft((current) => {
      const blocks = [...(current.blocks ?? [])];
      blocks[index] = { ...blocks[index], ...patch };
      return { ...current, blocks };
    });
  }

  function addBlock(type: Block["type"]) {
    const blank: Block =
      type === "TEXT"
        ? { type: "TEXT", text: "", format: "PLAIN" }
        : type === "IMAGE"
          ? { type: "IMAGE", objectId: "", alt: "" }
          : type === "LINK"
            ? { type: "LINK", text: "", href: "" }
            : { type: "TITLE", text: "" };
    setDraft((current) => ({ ...current, blocks: [...(current.blocks ?? []), blank] }));
  }

  function removeBlock(index: number) {
    setDraft((current) => ({
      ...current,
      blocks: (current.blocks ?? []).filter((_, i) => i !== index),
    }));
  }

  async function readReason(reply: Response | null): Promise<string | null> {
    if (!reply) return null;
    try {
      const body = (await reply.json()) as { reason?: string };
      return body.reason ?? null;
    } catch {
      return null;
    }
  }

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!canWrite) return;
    const tid = tenantId.trim();
    if (!tid) return;
    setBusy(true);
    setNotice(null);
    const body = { slug: draft.slug, title: draft.title, blocks: draft.blocks ?? [] };
    const creating = !selectedId;
    const reply = await fetch(
      creating
        ? `/api/platform/content-pages?${tenantQ()}`
        : `/api/platform/content-pages/${encodeURIComponent(selectedId)}?${tenantQ()}`,
      {
        method: creating ? "POST" : "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      const reason = await readReason(reply);
      setNotice(reason === "content-slug-conflict" ? phrases.contentSlugConflict : phrases.contentSaveFailed);
      return;
    }
    const saved = (await reply.json()) as Document;
    setSelectedId(saved.pageId ?? "");
    setDraft({
      pageId: saved.pageId,
      slug: saved.slug ?? "",
      title: saved.title ?? "",
      status: saved.status ?? "DRAFT",
      blocks: saved.blocks ?? [],
    });
    setNotice(phrases.contentSaved);
    await loadList();
  }

  async function onPublish(kind: "publish" | "unpublish") {
    if (!canWrite || !selectedId) return;
    setBusy(true);
    setNotice(null);
    const reply = await fetch(
      `/api/platform/content-pages/${encodeURIComponent(selectedId)}/${kind}?${tenantQ()}`,
      { method: "POST" },
    ).catch(() => null);
    setBusy(false);
    if (!reply?.ok) {
      setNotice(kind === "publish" ? phrases.contentPublishFailed : phrases.contentUnpublishFailed);
      return;
    }
    const saved = (await reply.json()) as Document;
    setDraft({
      pageId: saved.pageId,
      slug: saved.slug ?? "",
      title: saved.title ?? "",
      status: saved.status ?? "DRAFT",
      blocks: saved.blocks ?? [],
    });
    setNotice(kind === "publish" ? phrases.contentPublished : phrases.contentUnpublished);
    await loadList();
  }

  return (
    <div className="flex flex-col gap-6">
      <form onSubmit={onRefresh} className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span>{phrases.contentTenant}</span>
          <input
            value={tenantId}
            onChange={(event) => setTenantId(event.target.value)}
            className="rounded border px-2 py-1"
          />
        </label>
        <button type="submit" className="rounded border px-3 py-1 text-sm">
          {phrases.contentLookup}
        </button>
        {canWrite ? (
          <button type="button" onClick={newDraft} className="rounded border px-3 py-1 text-sm">
            {phrases.contentCreate}
          </button>
        ) : null}
      </form>

      {status === "idle" ? <p className="text-sm text-muted">{phrases.contentMissingTenant}</p> : null}
      {status === "error" ? <p className="text-sm text-muted">{phrases.contentLoadFailed}</p> : null}
      {notice ? <p className="text-sm">{notice}</p> : null}

      {status === "ok" ? (
        <ul className="flex flex-col gap-1 text-sm">
          {!rows.length ? <li className="text-muted">{phrases.contentEmpty}</li> : null}
          {rows.map((row) => (
            <li key={row.pageId}>
              <button type="button" className="underline" onClick={() => void openPage(row.pageId ?? "")}>
                {row.title} ({row.slug}) —{" "}
                {row.status === "PUBLISHED" ? phrases.contentStatusPublished : phrases.contentStatusDraft}
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      <form onSubmit={onSave} className="flex max-w-2xl flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span>{phrases.contentSlug}</span>
          <input
            value={draft.slug ?? ""}
            onChange={(event) => setDraft((current) => ({ ...current, slug: event.target.value }))}
            className="rounded border px-2 py-1"
            disabled={!canWrite}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span>{phrases.contentPageTitle}</span>
          <input
            value={draft.title ?? ""}
            onChange={(event) => setDraft((current) => ({ ...current, title: event.target.value }))}
            className="rounded border px-2 py-1"
            disabled={!canWrite}
          />
        </label>
        <p className="text-sm">
          {phrases.contentStatus}:{" "}
          {draft.status === "PUBLISHED" ? phrases.contentStatusPublished : phrases.contentStatusDraft}
        </p>
        <div className="flex flex-col gap-3">
          <h2 className="text-base font-medium">{phrases.contentBlocks}</h2>
          {(draft.blocks ?? []).map((block, index) => (
            <div key={`${block.blockId ?? "new"}-${index}`} className="flex flex-col gap-2 rounded border p-3">
              {block.type === "TITLE" || block.type === "TEXT" || block.type === "LINK" ? (
                <label className="flex flex-col gap-1 text-sm">
                  <span>
                    {block.type === "TITLE"
                      ? phrases.contentBlockTitle
                      : block.type === "LINK"
                        ? phrases.contentBlockLinkLabel
                        : phrases.contentBlockText}
                  </span>
                  <textarea
                    value={block.text ?? ""}
                    onChange={(event) => patchBlock(index, { text: event.target.value })}
                    className="rounded border px-2 py-1"
                    rows={block.type === "TEXT" ? 6 : 2}
                    disabled={!canWrite}
                  />
                </label>
              ) : null}
              {block.type === "TEXT" ? (
                <label className="flex flex-col gap-1 text-sm">
                  <span>{phrases.contentTextFormat}</span>
                  <select
                    value={block.format ?? "PLAIN"}
                    onChange={(event) => patchBlock(index, { format: event.target.value })}
                    className="rounded border px-2 py-1"
                    disabled={!canWrite}
                  >
                    <option value="PLAIN">{phrases.contentFormatPlain}</option>
                    <option value="MARKDOWN">{phrases.contentFormatMarkdown}</option>
                  </select>
                </label>
              ) : null}
              {block.type === "IMAGE" ? (
                <>
                  <label className="flex flex-col gap-1 text-sm">
                    <span>{phrases.contentBlockImage}</span>
                    <input
                      value={block.objectId ?? ""}
                      onChange={(event) => patchBlock(index, { objectId: event.target.value })}
                      className="rounded border px-2 py-1"
                      disabled={!canWrite}
                    />
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    <span>{phrases.contentBlockAlt}</span>
                    <input
                      value={block.alt ?? ""}
                      onChange={(event) => patchBlock(index, { alt: event.target.value })}
                      className="rounded border px-2 py-1"
                      disabled={!canWrite}
                    />
                  </label>
                </>
              ) : null}
              {block.type === "LINK" ? (
                <label className="flex flex-col gap-1 text-sm">
                  <span>{phrases.contentBlockHref}</span>
                  <input
                    value={block.href ?? ""}
                    onChange={(event) => patchBlock(index, { href: event.target.value })}
                    className="rounded border px-2 py-1"
                    disabled={!canWrite}
                  />
                </label>
              ) : null}
              {canWrite ? (
                <button type="button" className="self-start text-sm underline" onClick={() => removeBlock(index)}>
                  {phrases.contentRemoveBlock}
                </button>
              ) : null}
            </div>
          ))}
          {canWrite ? (
            <div className="flex flex-wrap gap-2">
              <button type="button" className="rounded border px-2 py-1 text-sm" onClick={() => addBlock("TITLE")}>
                {phrases.contentAddTitle}
              </button>
              <button type="button" className="rounded border px-2 py-1 text-sm" onClick={() => addBlock("TEXT")}>
                {phrases.contentAddText}
              </button>
              <button type="button" className="rounded border px-2 py-1 text-sm" onClick={() => addBlock("IMAGE")}>
                {phrases.contentAddImage}
              </button>
              <button type="button" className="rounded border px-2 py-1 text-sm" onClick={() => addBlock("LINK")}>
                {phrases.contentAddLink}
              </button>
            </div>
          ) : (
            <p className="text-sm text-muted">{phrases.contentNoWrite}</p>
          )}
        </div>
        {canWrite ? (
          <div className="flex flex-wrap gap-2">
            <button type="submit" disabled={busy} className="rounded border px-3 py-1 text-sm">
              {selectedId ? phrases.contentSave : phrases.contentCreate}
            </button>
            {selectedId ? (
              <>
                <button
                  type="button"
                  disabled={busy}
                  className="rounded border px-3 py-1 text-sm"
                  onClick={() => void onPublish("publish")}
                >
                  {phrases.contentPublish}
                </button>
                <button
                  type="button"
                  disabled={busy}
                  className="rounded border px-3 py-1 text-sm"
                  onClick={() => void onPublish("unpublish")}
                >
                  {phrases.contentUnpublish}
                </button>
              </>
            ) : null}
          </div>
        ) : null}
      </form>
    </div>
  );
}
