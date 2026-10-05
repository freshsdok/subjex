# CI / 持续集成

Canonical workflow: [`.github/workflows/build.yml`](../../.github/workflows/build.yml).

正式工作流在 `.github/workflows/build.yml`。每次 push 和 pull request 会：

1. **test** — `mvn -B test`（Java 21 / Temurin；GitHub ubuntu runner 有 Docker，Testcontainers 真库启动测试会跑）
2. **web** — 在 `web/` 下 `npm ci`、`npm run typecheck`、`npm test`、`npm run build`（Node 22）

Do not keep a second copy of the workflow YAML under `docs/ci/`. This directory is only a pointer.

不要在 `docs/ci/` 再放一份工作流 YAML；本目录只作指引。

## Push note / 推送说明

Creating or updating `.github/workflows/*` via `git push` or the Git Data API requires a token with the **`workflow`** scope (in addition to `repo`). Without it, GitHub rejects the path even when the rest of the tree is fine.

经 `git push` 或 Git Data API 创建/更新 `.github/workflows/*` 时，令牌除 `repo` 外还需要 **`workflow`** 权限；否则 GitHub 会拒绝该路径。
