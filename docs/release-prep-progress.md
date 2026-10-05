# 发布准备进度 / Release-prep progress

- 日期：2026-10-05（UTC+8）
- 对照评估：[`pre-release-assessment.md`](pre-release-assessment.md)
- 本机提交（未 push）：见下方 SHA

## 已完成 / Done

### B1 — `mvn test` 全绿
- 做法：从 `sample-consumer` / `entry-gateway` 的 `ModuleBoundaryArchTest` 副本中删掉本模块测试 classpath 匹配不到类的规则（`failOnEmptyShould` 保持开启）。
  - `sample-consumer`：去掉 `gateway_does_not_depend_on_the_host`（无 gateway 类）。
  - `entry-gateway`：去掉 `host_does_not_depend_on_the_sample`、`sample_does_not_depend_on_the_host`、`host_does_not_depend_on_the_gateway`（无 host/sample 类）。
  - “契约不依赖进程”与“不用模型层”两边都保留；宿主/示例规则在 `sample-consumer`，“网关不依赖宿主”在 `entry-gateway`。
- 验证：
  - `mvn -o -B -pl sample-consumer,entry-gateway -am test` → **BUILD SUCCESS**（sample-consumer 13、entry-gateway 11）。
  - `mvn -o -B -fae test`（全 reactor）→ **BUILD SUCCESS**，9 个模块 SUCCESS，**160 测试 / 0 失败 / 0 错误 / 0 跳过**（有 Docker，`VendorStartupTest` 真跑了 MySQL 8.4 与 PostgreSQL 16）。
- 提交：`eccd458` — `test: keep only module-applicable ArchUnit boundary rules (B1)`

### B3（文档部分）— README / ARCHITECTURE / CHANGELOG / SECURITY
- README：状态与“它不是什么”、Redis 仅用于控制台会话、完整测试清单（含网关/OTLP/多表单）、compose 内容、可观测、三份 Dockerfile、控制台会话说明。
- ARCHITECTURE：§13–15 中英同号改为中文主节 + English summary 子节；标注 §2/§7/§10/§11/§12 过时表述；新增 §16 发布前状态。
- 新增 `CHANGELOG.md`（Unreleased / 拟 `v0.1.0-alpha.1`）与 `SECURITY.md`（报告渠道 + 已知限制：Redis Basic 头、出箱口令无 TLS、XFF 伪造、自动建租户、匿名指标、local 种子操作员）。
- 提交：
  - `7cff7ed` — `docs: align README and ARCHITECTURE with current tree (B3)`
  - `233d800` — `docs: add CHANGELOG and SECURITY with pre-release known limits (B3)`

### 安全加固（小切片）— 网关 XFF 信任条件化
- `entry-gateway` 默认只用远端地址做限流客户端标识；新配置 `gateway.trusted-proxies`（`GATEWAY_TRUSTED_PROXIES`，IP/CIDR，默认空）命中直连方时才从右往左读 `X-Forwarded-For`。`server.forward-headers-strategy: none` 防止 K8s 下 Tomcat 自动采信。
- 新增 `TrustedProxies`、`ForwardedHeaderSettings`；`ClientIdentity` 改为 bean。entry-gateway 测试 11 → 25。
- 提交：`fix: only trust X-Forwarded-For when gateway allows it`

### 部署切片 — compose 加入 sample-consumer
- `deploy/compose/docker-compose.yml`：新增 `sample-consumer` 服务；`platform-app` 设置 `SAMPLE_CONSUMER_HOST=sample-consumer` / `SAMPLE_CONSUMER_PORT=19081`（与 k8s 一致）；保留 Redis、`--scale platform-app=2`、gateway；不含 `web/`。
- 同步 `deploy/compose/README.md`、根 README 与 CHANGELOG 已知限制表述。
- 本切片未强制全栈镜像构建/冒烟（可选且须很快）。
- 提交：`deploy: add sample-consumer to local compose stack`

## 刻意跳过 / Skipped this slice

- **B2 — 把 CI 移到 `.github/workflows/`**：需要有 `workflow` 权限的令牌；当前环境没有，只记笔记。工作流仍在 `docs/ci/build.yml`。
- **完整 compose 镜像构建 / 全栈实测**：可选；compose 已含 `sample-consumer`，仍不含 `web/`。本切片未强制 `--build` 冒烟。

## 仍阻塞正式 tag / Still blocking a public tag

1. **B2**：CI 未运行（需 workflow 权限把文件移到 `.github/workflows/`，并建议补上 `web/` 的 typecheck/test/build）。
2. **未实测 compose 全栈**（可选但评估列为前 5 项动作第 4 项）：compose 已含 `sample-consumer`；仍建议跑一次 `docker compose … up --build --scale platform-app=2` + `smoke-load.sh` 验证健康检查与投递。
3. **安全加固仍是非阻塞已知限制**（可带着发 alpha，但要写进 release notes）：~~XFF 信任条件化~~（已修）、Redis 会话改存短期令牌而非 Basic 头、出箱 TLS/不带明文口令、操作员开通方式。

## 建议的下一个标签

修完 B2、CI 绿之后打 **`v0.1.0-alpha.1`**（pre-release），不要标稳定 `0.1.0`。当前仍是 `0.1.0-SNAPSHOT`，不要打 tag。
