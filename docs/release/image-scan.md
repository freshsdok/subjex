# Image scan / 镜像扫描（P1）

## Purpose / 目的

Reproducible builds of the three Dockerfiles and a HIGH/CRITICAL vulnerability gate before any `v0.1.0-alpha.1` tag.
三个 Dockerfile 可复现构建；打 `v0.1.0-alpha.1` 前须过 HIGH/CRITICAL 漏洞门禁。

## Images / 镜像

| Dockerfile | Image tag |
| --- | --- |
| `platform-app/Dockerfile` | `subjex/platform-app:0.1.0-SNAPSHOT` |
| `entry-gateway/Dockerfile` | `subjex/entry-gateway:0.1.0-SNAPSHOT` |
| `sample-consumer/Dockerfile` | `subjex/sample-consumer:0.1.0-SNAPSHOT` |

All run as **uid 10001** (`USER 10001:10001`). Public registry push is optional; local/`docker load` is enough.
全部以 **uid 10001** 运行。公开仓库推送可选；本机构建或 `docker load` 即可。

## CI / 持续集成

Job `image-scan` in `.github/workflows/build.yml`:

1. `docker build` each Dockerfile from the repository root.
2. Assert `Config.User` is `10001` / `10001:10001` (not root).
3. [Trivy](https://github.com/aquasecurity/trivy) scan severity `HIGH,CRITICAL` with `exit-code: 1` (unfixed CRITICAL/HIGH fails the job — tag gate).
4. Upload `trivy-*-v0.1.0-alpha.1` artifacts (90-day retention) as the scan record for the alpha tag.

## Local reproduce / 本机复现

```shell
docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .
docker build -f entry-gateway/Dockerfile -t subjex/entry-gateway:0.1.0-SNAPSHOT .
docker build -f sample-consumer/Dockerfile -t subjex/sample-consumer:0.1.0-SNAPSHOT .
# Requires trivy on PATH / 需本机安装 trivy
trivy image --severity HIGH,CRITICAL --exit-code 1 subjex/platform-app:0.1.0-SNAPSHOT
```

**No successful CI artifact ⇒ do not cut the alpha tag.** 无成功扫描产物则不得打 alpha tag。

## Alpha residual / P1 (2026-10-09)

Boot **4.0.8** migration (Boot4-1) lands Framework **7.0.9** / spring-webmvc fix line — expect CVE-2026-47884 / CVE-2026-47890 **cleared**.

**Boot4-2 overrides (2026-10-09):** `tomcat.version=11.0.26` (≥11.0.25), `jackson-bom.version=3.1.7`, `jackson-2-bom.version=2.21.7` (clears leftover `com.fasterxml.jackson` 2.21.5 from flyway/swagger). Do not tag `v0.1.0-alpha.1` until image-scan job is green.

Boot4-1 已升 Framework 7.0.9；推送后看 CI image-scan；全绿再打 alpha tag。
