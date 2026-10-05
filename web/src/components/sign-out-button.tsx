"use client";

import { useRouter } from "next/navigation";

// Sign-out button — 退出按钮：删会话后回登录页。
export function SignOutButton({ label }: { label: string }) {
  const router = useRouter();
  async function signOut() {
    await fetch("/api/session", { method: "DELETE" });
    router.replace("/login");
    router.refresh();
  }
  return (
    <button onClick={signOut} className="rounded-md border border-border px-3 py-1 text-sm hover:bg-background">
      {label}
    </button>
  );
}
