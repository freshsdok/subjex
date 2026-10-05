import { cookies } from "next/headers";
import type { components } from "@/api/schema";
import { readPlatform } from "./platform-reader";

type SkinListDocument = components["schemas"]["SkinListDocument"];

export const skinCookieName = "subjex_skin";

// Platform skin variable -> console CSS variable — 平台皮肤变量到控制台 CSS 变量的对照。
const consoleVariableBySkinVariable: Record<string, string> = {
  "--page-background": "--background",
  "--page-text": "--foreground",
  "--page-muted": "--muted",
  "--page-line": "--border",
};

// Chosen skin and its CSS variables — 当前皮肤及其 CSS 变量；没有 page.read 或未选皮肤时用默认样式。
export async function currentSkin(): Promise<{
  skinNames: string[];
  chosenSkinName: string | null;
  skinStyle: Record<string, string>;
}> {
  const { body } = await readPlatform<SkinListDocument>("skins");
  const skins = body?.skins ?? [];
  const chosenSkinName = (await cookies()).get(skinCookieName)?.value ?? null;
  const chosenSkin = skins.find((skin) => skin.name === chosenSkinName);
  const skinStyle = skinStyleFor(chosenSkin?.variables);
  return {
    skinNames: skins.map((skin) => skin.name ?? "").filter(Boolean),
    chosenSkinName: chosenSkin ? chosenSkinName : null,
    skinStyle,
  };
}

// Pure mapping, kept separate for tests — 纯映射函数，单独导出便于测试。
export function skinStyleFor(skinVariables: Record<string, string> | undefined): Record<string, string> {
  const skinStyle: Record<string, string> = {};
  if (!skinVariables) return skinStyle;
  for (const [skinVariable, colorValue] of Object.entries(skinVariables)) {
    const consoleVariable = consoleVariableBySkinVariable[skinVariable];
    if (consoleVariable) skinStyle[consoleVariable] = colorValue;
  }
  skinStyle["--surface"] = skinStyle["--background"] ?? "#ffffff";
  return skinStyle;
}
