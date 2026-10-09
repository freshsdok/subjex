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

## Alpha residual (Boot 3.5.16) / Alpha 残留（2026-10-09）

Boot **3.5.16** is the last OSS 3.5 line. Root `pom.xml` overrides (managed properties):

| Property | Override | Clears |
|----------|----------|--------|
| `tomcat.version` | `10.1.60` | Tomcat embed CRITICAL (was 10.1.55; **10.1.58 not on Maven Central** — use 10.1.60) |
| `jackson-bom.version` | `2.21.7` | jackson-core / jackson-databind HIGH |
| `postgresql.version` | `42.7.14` | postgresql HIGH |

**Residual — blocks P1 / alpha tag:** `spring-webmvc` **6.2.19** CRITICAL (CVE-2026-47884, CVE-2026-47890). Trivy’s fix line is Spring Framework **7.0.9** (Spring Boot **4.0.x**, Jackson **3.x**). Probes:

- `spring-framework.version=7.0.9` on Boot 3.5.16: **compiles** but tests fail (`SpringExtension` / JUnit `NoSuchMethodError`).
- Boot **4.0.8** parent: POM fails immediately (testcontainers no longer in Boot BOM without explicit versions) + Jackson 3 migration — **too disruptive for alpha**.

**Ask owner:** either (a) open a Boot 4 track before alpha, or (b) written P1 waiver for the two spring-webmvc CRITICAL CVEs until Boot 4. Do **not** tag `v0.1.0-alpha.1` while image-scan stays red unless waived.

Boot **3.5.16** 末代；Tomcat/Jackson/PostgreSQL 已覆盖。**spring-webmvc CRITICAL 需 Boot 4**；Framework 7 硬叠 3.5 测不过。未书面豁免或升 Boot 4 前勿打 tag。
