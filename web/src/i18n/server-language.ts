import { cookies } from "next/headers";
import { languageCookieName, phrasesFor, pickLanguage, type LanguageCode, type PhraseBook } from "./phrases";

// Reads the chosen language on the server — 服务端读取当前语言（cookie，默认中文）。
export async function currentLanguage(): Promise<{ language: LanguageCode; phrases: PhraseBook }> {
  const language = pickLanguage((await cookies()).get(languageCookieName)?.value);
  return { language, phrases: phrasesFor(language) };
}
