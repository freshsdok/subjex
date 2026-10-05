import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

// Vitest config — 单元测试配置：只测纯逻辑，@ 指向 src。
export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  test: { include: ["tests/**/*.test.ts"], environment: "node" },
});
