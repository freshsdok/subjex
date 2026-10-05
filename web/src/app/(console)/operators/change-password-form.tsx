"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

export function ChangePasswordForm({ phrases }: { phrases: PhraseBook }) {
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setProblem(null);
    setNotice(null);
    const reply = await fetch("/api/platform/operators/me/password", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ currentPassword, newPassword }),
    }).catch(() => null);
    setSaving(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.passwordChangeFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setCurrentPassword("");
    setNewPassword("");
    setNotice(phrases.passwordChangedNotice);
  }

  return (
    <form onSubmit={onSubmit} className="mt-4 flex max-w-md flex-col gap-3 rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{phrases.changePasswordTitle}</h2>
      <label className="flex flex-col gap-1 text-xs">
        {phrases.currentPasswordLabel}
        <input
          type="password"
          autoComplete="current-password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          className="rounded-md border border-border bg-background px-2 py-1"
          required
        />
      </label>
      <label className="flex flex-col gap-1 text-xs">
        {phrases.newPasswordLabel}
        <input
          type="password"
          autoComplete="new-password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          className="rounded-md border border-border bg-background px-2 py-1"
          minLength={8}
          required
        />
      </label>
      {problem && <p className="text-xs text-red-700">{problem}</p>}
      {notice && (
        <p role="status" className="text-xs text-green-700">
          {notice}
        </p>
      )}
      <button
        type="submit"
        disabled={saving}
        className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
      >
        {saving ? phrases.savingAction : phrases.changePasswordAction}
      </button>
    </form>
  );
}
