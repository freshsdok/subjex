# subjex console — 控制台

Next.js 16 + React 19 的操作员控制台，经服务端代理访问平台 `/api/v1`。

## 本地运行

1. 启动平台（`SPRING_PROFILES_ACTIVE=local`，见仓库根目录 `docs/quickstart.md`）。
2. 在此目录：

```bash
npm install
npm run dev
```

浏览器打开 <http://localhost:3000>。默认本地操作员见 quickstart。

环境变量：

- `SUBJEX_API_BASE`：平台根地址，默认 `http://127.0.0.1:8080`。

## 常用命令

- `npm run typecheck` — TypeScript 检查
- `npm run test` — Vitest 单元测试
- `npm run build` — 生产构建
- `npm run gen:api` — 用 `openapi.json` 重新生成 `src/api/schema.d.ts`

## 设计

见 [docs/design-notes.md](docs/design-notes.md)。进度切片见 [PROGRESS.md](PROGRESS.md)。

## Sessions (node 5)

Set `SESSION_REDIS_URL=redis://127.0.0.1:16379` (or `REDIS_URL`) so operator sessions are shared across console replicas. Without it, sessions stay in process memory (fine for local single-node and unit tests).

节点五：配置 `SESSION_REDIS_URL`（或 `REDIS_URL`）后，操作员会话进 Redis，多副本控制台共享登录态；未配置时仍用进程内存。
