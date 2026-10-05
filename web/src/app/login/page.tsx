import { currentLanguage } from "@/i18n/server-language";
import { SignInForm } from "./sign-in-form";

// Login page — 登录页：只有一个表单，失败时就地说明原因。
export default async function LoginPage() {
  const { phrases } = await currentLanguage();
  return (
    <main className="flex flex-1 items-center justify-center p-6">
      <section className="w-full max-w-sm rounded-lg border border-border bg-surface p-6 shadow-sm">
        <p className="text-sm text-muted">{phrases.productName}</p>
        <h1 className="mb-1 text-xl font-semibold">{phrases.signInTitle}</h1>
        <p className="mb-5 text-sm text-muted">{phrases.signInHint}</p>
        <SignInForm phrases={phrases} />
      </section>
    </main>
  );
}
