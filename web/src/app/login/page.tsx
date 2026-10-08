import { Suspense } from "react";
import { AppearanceSwitcher } from "@/components/appearance-switcher";
import { currentLanguage } from "@/i18n/server-language";
import { SignInForm } from "./sign-in-form";

// Login page — 登录页：口令表单；OIDC 启用时另有企业登录入口。
export default async function LoginPage() {
  const { language, phrases } = await currentLanguage();
  return (
    <main className="flex flex-1 items-center justify-center p-6">
      <section className="w-full max-w-sm rounded-lg border border-border bg-surface p-6 shadow-sm">
        {/* Language choice before signing in — 登录前就能选语言。 */}
        <div className="mb-3 flex items-center justify-between text-sm">
          <p className="text-muted">{phrases.productName}</p>
          <AppearanceSwitcher phrases={phrases} language={language} skinNames={[]} chosenSkinName={null} />
        </div>
        <h1 className="mb-1 text-xl font-semibold">{phrases.signInTitle}</h1>
        <p className="mb-5 text-sm text-muted">{phrases.signInHint}</p>
        <Suspense fallback={<p className="text-sm text-muted">{phrases.signingIn}</p>}>
          <SignInForm phrases={phrases} />
        </Suspense>
      </section>
    </main>
  );
}
