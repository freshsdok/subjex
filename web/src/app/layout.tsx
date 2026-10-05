import type { Metadata } from "next";
import type { ReactNode } from "react";
import { currentLanguage } from "@/i18n/server-language";
import "./globals.css";

export const metadata: Metadata = {
  title: "subjex console",
  description: "Operator console for the subjex platform",
};

// Root layout — 根布局：按语言 cookie 设置 <html lang>。
// Use ReactNode (not generated LayoutProps) so `tsc` works on a clean checkout without `.next/types`.
export default async function RootLayout({ children }: { children: ReactNode }) {
  const { language } = await currentLanguage();
  return (
    <html lang={language === "zh" ? "zh-CN" : "en"} className="h-full antialiased">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
