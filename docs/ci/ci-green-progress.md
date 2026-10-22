# CI green progress / CI 复绿进度

- Tip baseline: `efb784d` (failed run [39216821681](https://github.com/freshsdok/subjex/actions/runs/39216821681))
- Owner order #1 — **local commits only** until push requested
- Slice goal: `mvn test` + Trivy image-scan×3 green; web / clients-uni-app already green

## Failures inventory (from CI logs) / 失败盘点

| Job | Symptom | Root cause |
| --- | --- | --- |
| `test` | `PagesRuntimeAfterPromoteSecurityTest` ×2 | WF-RT-1 added classpath `repair-ticket`; tests still assumed absent → 200 / `tenant_missing` 403 |
| `test` | `DeadLetterReplayTest.replayRepublishesAndRemovesLetter` ×2 (flaky) | Peer counted `accepted` **after** `writeOutcome`; publisher could observe PUBLISHED with `accepted==0` |
| `image-scan` ×3 | Trivy HIGH on `usr/bin/pebble` | Floating `eclipse-temurin:21-jre` → Ubuntu 26.04 + pebble Go 1.27.1 (CVE-2026-78667/78669/97031) |

## Slices / 切片

| ID | Status | Notes |
| --- | --- | --- |
| **CI-1a** | **done** (local) | Fix Pages ghost key + DeadLetter accept-before-ACK race |
| **CI-1b** | **done** (local) | Pin runtime `FROM eclipse-temurin:21.0.12.1_1-jre-noble`; `docs/release/image-scan.md` note |
| **CI-1c** | pending push | User must push for GitHub Actions confirmation |

## Local verification / 本机核对

- `mvn -pl platform-app -am test -Dtest=DeadLetterReplayTest,PagesRuntimeAfterPromoteSecurityTest` — see commit message
- Noble base has **no** `/usr/bin/pebble` (Mac docker inspect)
- Full `mvn -B -fae test` @ Asia/Shanghai 2026-10-22: **BUILD SUCCESS** (platform-app 697 tests, 0 fail, 2 skipped; no Docker → VendorStartup skipped)

## Next / 下一步

Owner order **#2 PC-5** (uni-app consumes `GET /ui-permissions`) after push/CI confirm if desired.

## Trivy local note / 本机 Trivy

- Mac docker: `eclipse-temurin:21.0.12.1_1-jre-noble` has **no** `/usr/bin/pebble`
- CI failure surface was **only** pebble HIGH CVEs (OS/jar were 0)
- Local `trivy image` blocked downloading vuln DB (GCR timeout) — GitHub Actions image-scan remains the gate after push

