import type { Metadata } from "next";
import { currentLanguage } from "@/i18n/server-language";
import "./globals.css";

export const metadata: Metadata = {
  title: "subjex console",
  description: "Operator console for the subjex platform",
};

// Root layout — 根布局：按语言 cookie 设置 <html lang>。
export default async function RootLayout({ children }: LayoutProps<"/">) {
  const { language } = await currentLanguage();
  return (
    <html lang={language === "zh" ? "zh-CN" : "en"} className="h-full antialiased">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
