import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

// Page heading with a one-line explanation — 页面标题加一句说明，让人知道这一页是干什么的。
export function PageHeading({ title, hint }: { title: string; hint: string }) {
  return (
    <header className="mb-5">
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-1 text-sm text-muted">{hint}</p>
    </header>
  );
}

// Explains a 403 instead of showing an empty page — 遇到 403 时说明缺哪个权限，而不是给一张空白页。
export function ForbiddenNotice({ phrases, permission }: { phrases: PhraseBook; permission: string }) {
  return (
    <section role="status" className="max-w-xl rounded-lg border border-border bg-surface p-5">
      <h1 className="mb-2 text-lg font-semibold">{phrases.forbiddenTitle}</h1>
      <p className="text-sm text-muted">{fillPhrase(phrases.forbiddenBody, { permission })}</p>
    </section>
  );
}

// Non-403 failure — 其他读取失败。
export function LoadFailedNotice({ phrases, status }: { phrases: PhraseBook; status: number }) {
  return (
    <p role="alert" className="text-sm text-danger">
      {fillPhrase(phrases.loadFailed, { status })}
    </p>
  );
}
