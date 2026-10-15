"use client";

import { useCallback, useEffect, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

/** Max upload size — 与 JdbcEntityBlobStore.MAX_BYTES 对齐（5 MiB）。 */
export const BLOB_MAX_BYTES = 5 * 1024 * 1024;

/** Use chunked upload above this size (CHUNK-1) — 超过此大小走分片上传。 */
export const BLOB_CHUNK_THRESHOLD = 256 * 1024;

type BlobMeta = {
  blobId?: string;
  fieldName?: string;
  contentType?: string;
  byteSize?: number;
  createdAt?: string;
};

type BlobsReply = { blobs?: BlobMeta[] };

/**
 * EntityBlobPanel — SHARED-3 multipart + CHUNK-1 chunked upload for entity attachments.
 * Lists / uploads / downloads / deletes via /api/platform/.../blobs.
 * SHARED-3 multipart；CHUNK-1 大文件分片；经平台代理到 EntityBlobEndpoint。
 */
export function EntityBlobPanel({
  entityKey,
  recordId,
  phrases,
  tenantId,
}: {
  entityKey: string;
  recordId: string;
  phrases: PhraseBook;
  tenantId?: string;
}) {
  const [blobs, setBlobs] = useState<BlobMeta[]>([]);
  const [fieldName, setFieldName] = useState("attachment");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const basePath = `/api/platform/entities/${encodeURIComponent(entityKey)}/records/${encodeURIComponent(recordId)}/blobs`;

  const tenantHeaders = useCallback((): Record<string, string> => {
    const headers: Record<string, string> = {};
    if (tenantId && tenantId.trim() !== "") {
      headers["X-Tenant-Id"] = tenantId.trim();
    }
    return headers;
  }, [tenantId]);

  const loadList = useCallback(async () => {
    setError(null);
    const reply = await fetch(basePath, {
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    if (!reply) {
      setError(fillPhrase(phrases.blobLoadFailed, { status: 0 }));
      return;
    }
    if (reply.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply.ok) {
      setError(fillPhrase(phrases.blobLoadFailed, { status: reply.status }));
      return;
    }
    const body = (await reply.json().catch(() => null)) as BlobsReply | null;
    setBlobs(Array.isArray(body?.blobs) ? body!.blobs! : []);
  }, [basePath, phrases.blobLoadFailed, tenantHeaders]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  async function uploadMultipart(name: string, chosen: File): Promise<Response | null> {
    const form = new FormData();
    form.append("file", chosen);
    const url = `${basePath}?fieldName=${encodeURIComponent(name)}`;
    return fetch(url, {
      method: "POST",
      headers: tenantHeaders(),
      body: form,
    }).catch(() => null);
  }

  /** CHUNK-1: init → PUT chunks → complete — 分片：初始化 → 写分片 → 完成。 */
  async function uploadChunked(name: string, chosen: File): Promise<Response | null> {
    const initHeaders: Record<string, string> = {
      ...tenantHeaders(),
      "content-type": "application/json",
    };
    const initReply = await fetch(`${basePath}/uploads`, {
      method: "POST",
      headers: initHeaders,
      body: JSON.stringify({
        fieldName: name,
        contentType: chosen.type || "application/octet-stream",
        totalBytes: chosen.size,
      }),
    }).catch(() => null);
    if (!initReply || !initReply.ok) return initReply;
    const initBody = (await initReply.json().catch(() => null)) as {
      uploadId?: string;
      chunkSize?: number;
      chunkCount?: number;
    } | null;
    const uploadId = initBody?.uploadId;
    const chunkSize = initBody?.chunkSize ?? BLOB_CHUNK_THRESHOLD;
    const chunkCount = initBody?.chunkCount ?? 0;
    if (!uploadId) {
      return initReply;
    }
    try {
      for (let index = 0; index < chunkCount; index++) {
        const start = index * chunkSize;
        const end = Math.min(start + chunkSize, chosen.size);
        const slice = chosen.slice(start, end);
        const buf = await slice.arrayBuffer();
        const chunkReply = await fetch(
          `${basePath}/uploads/${encodeURIComponent(uploadId)}/chunks/${index}`,
          {
            method: "PUT",
            headers: {
              ...tenantHeaders(),
              "content-type": "application/octet-stream",
            },
            body: buf,
          },
        ).catch(() => null);
        if (!chunkReply || !chunkReply.ok) {
          await fetch(`${basePath}/uploads/${encodeURIComponent(uploadId)}`, {
            method: "DELETE",
            headers: tenantHeaders(),
          }).catch(() => null);
          return chunkReply;
        }
      }
      return fetch(`${basePath}/uploads/${encodeURIComponent(uploadId)}/complete`, {
        method: "POST",
        headers: tenantHeaders(),
      }).catch(() => null);
    } catch {
      await fetch(`${basePath}/uploads/${encodeURIComponent(uploadId)}`, {
        method: "DELETE",
        headers: tenantHeaders(),
      }).catch(() => null);
      return null;
    }
  }

  async function upload() {
    setNotice(null);
    setError(null);
    if (!file) {
      setError(phrases.blobNoFile);
      return;
    }
    if (file.size > BLOB_MAX_BYTES) {
      setError(phrases.blobTooLarge);
      return;
    }
    setBusy(true);
    const name = fieldName.trim() || "attachment";
    // Small files: multipart; larger: CHUNK-1 chunked sessions.
    // 小文件 multipart；更大走 CHUNK-1 分片会话。
    const reply =
      file.size > BLOB_CHUNK_THRESHOLD
        ? await uploadChunked(name, file)
        : await uploadMultipart(name, file);
    setBusy(false);
    if (!reply) {
      setError(fillPhrase(phrases.blobUploadFailed, { status: 0, reason: "" }));
      return;
    }
    if (reply.status === 401) {
      window.location.href = "/login";
      return;
    }
    if (!reply.ok) {
      const body = (await reply.json().catch(() => null)) as { reason?: string } | null;
      const reason = body?.reason ? `: ${body.reason}` : "";
      setError(fillPhrase(phrases.blobUploadFailed, { status: reply.status, reason }));
      return;
    }
    setFile(null);
    setNotice(phrases.blobUploadAction);
    await loadList();
  }

  async function remove(blobId: string) {
    setNotice(null);
    setError(null);
    setBusy(true);
    const reply = await fetch(`${basePath}/${encodeURIComponent(blobId)}`, {
      method: "DELETE",
      headers: tenantHeaders(),
    }).catch(() => null);
    setBusy(false);
    if (!reply || !reply.ok) {
      setError(fillPhrase(phrases.blobDeleteFailed, { status: reply?.status ?? 0 }));
      return;
    }
    await loadList();
  }

  return (
    <section className="mt-6 space-y-3 rounded-lg border border-border bg-surface p-4">
      <header className="space-y-1">
        <h3 className="text-sm font-medium">{phrases.blobPanelTitle}</h3>
        <p className="text-xs text-muted">{phrases.blobPanelHint}</p>
      </header>

      <div className="flex flex-wrap items-end gap-3">
        <label className="space-y-1 text-xs">
          <span className="text-muted">{phrases.blobFieldNameLabel}</span>
          <input
            className="block w-40 rounded border border-border bg-background px-2 py-1 text-sm"
            value={fieldName}
            onChange={(e) => setFieldName(e.target.value)}
            disabled={busy}
          />
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted">{phrases.blobChooseFileLabel}</span>
          <input
            type="file"
            className="block max-w-xs text-sm"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            disabled={busy}
          />
        </label>
        <button
          type="button"
          className="rounded bg-accent px-3 py-1.5 text-sm text-white disabled:opacity-50"
          onClick={() => void upload()}
          disabled={busy}
        >
          {busy ? phrases.blobUploading : phrases.blobUploadAction}
        </button>
        <button
          type="button"
          className="rounded border border-border px-3 py-1.5 text-sm disabled:opacity-50"
          onClick={() => void loadList()}
          disabled={busy}
        >
          {phrases.blobRefreshAction}
        </button>
      </div>

      {error ? <p className="text-sm text-red-600">{error}</p> : null}
      {notice && !error ? <p className="text-sm text-muted">{notice}</p> : null}

      {blobs.length === 0 ? (
        <p className="text-sm text-muted">{phrases.blobEmpty}</p>
      ) : (
        <ul className="divide-y divide-border rounded border border-border">
          {blobs.map((blob) => {
            const id = blob.blobId ?? "";
            const href = `${basePath}/${encodeURIComponent(id)}`;
            return (
              <li key={id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
                <div className="min-w-0 space-y-0.5">
                  <div className="font-mono text-xs">{id}</div>
                  <div className="text-muted">
                    {blob.fieldName ?? "—"} · {blob.contentType ?? "—"} ·{" "}
                    {fillPhrase(phrases.blobByteSizeLabel, { size: blob.byteSize ?? 0 })}
                    {blob.createdAt ? ` · ${blob.createdAt}` : ""}
                  </div>
                </div>
                <div className="flex gap-2">
                  <a className="text-accent underline" href={href} target="_blank" rel="noreferrer">
                    {phrases.blobDownloadAction}
                  </a>
                  <button
                    type="button"
                    className="text-red-600 underline disabled:opacity-50"
                    onClick={() => void remove(id)}
                    disabled={busy || !id}
                  >
                    {phrases.blobDeleteAction}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
