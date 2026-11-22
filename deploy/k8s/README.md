# Kubernetes manifests / Kubernetes 清单

These files describe how `platform-app` and `sample-consumer` would run as two Deployments.
They are not applied. The build does not call `kubectl`, and `mvn test` does not build container images.
There is no cluster in the test path, and no HorizontalPodAutoscaler.

这些文件描述 `platform-app` 和 `sample-consumer` 作为两个 Deployment 时的样子。
它们不会被应用。构建不调用 `kubectl`，`mvn test` 也不构建容器镜像。
测试路径上没有集群，也没有 HorizontalPodAutoscaler。

Image names (`subjex/platform-app:0.1.0-SNAPSHOT`, `subjex/sample-consumer:0.1.0-SNAPSHOT`) are what `platform-app/Dockerfile` and `sample-consumer/Dockerfile` build. They are not pushed to any registry; load them into your cluster yourself. Both images run as uid 10001, and the manifests set `runAsNonRoot`.

镜像名（`subjex/platform-app:0.1.0-SNAPSHOT`、`subjex/sample-consumer:0.1.0-SNAPSHOT`）就是 `platform-app/Dockerfile` 与 `sample-consumer/Dockerfile` 构建出的镜像。它们没有推送到任何镜像仓库，请自行装进集群。两个镜像都以 uid 10001 运行，清单设置了 `runAsNonRoot`。

```shell
docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .
docker build -f sample-consumer/Dockerfile -t subjex/sample-consumer:0.1.0-SNAPSHOT .
```

platform-app in these manifests sets `SPRING_PROFILES_ACTIVE=prod` and does not run the `local` profile, so no operator is seeded. Combining `prod`/`production` with `local` fails fast at startup (`ProductionProfileGuard`). Provision operator rows first (see `docs/operator-permissions.md`); sample-consumer signs in with `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD` (do not put `change-me` or the laptop `platform-operator` password into these manifests).

清单里的 platform-app 设置 `SPRING_PROFILES_ACTIVE=prod`，不启用 `local` profile，因此不会写入操作员。将 `prod`/`production` 与 `local` 同时打开会在启动时失败（`ProductionProfileGuard`）。请先开通操作员表行（见 `docs/operator-permissions.md`）；sample-consumer 用 `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD` 登录（不要把 `change-me` 或本机 `platform-operator` 口令写进这些清单）。

Outbox delivery (fail-closed): create Secret `outbox-delivery` (`hmac-secret`, `truststore-password` / `keystore-password`) and Secret `outbox-delivery-tls` (PKCS12 files mounted at `/var/run/outbox-tls`). `OUTBOX_TLS_ENABLED=true` is set in the manifests.
出箱投递（失败关闭）：创建 Secret `outbox-delivery` 与挂载 PKCS12 的 `outbox-delivery-tls`。清单已设 `OUTBOX_TLS_ENABLED=true`。

The human page is `GET /deploy` on platform-app. It reads these files. It does not apply them.

给人看的页面是 platform-app 上的 `GET /deploy`。它读这些文件，不应用它们。
---
entry-gateway.yaml — HTTP entry gateway (not applied by tests).

## Management port and NetworkPolicy / 管理端口与网络策略（P2）

Probes and Prometheus use `management.server.port` (not the business port). Sample isolation: `networkpolicy-sample.yaml`.
探针与 Prometheus 走管理端口（非业务口）。隔离样例见 `networkpolicy-sample.yaml`。

## Single-replica gate / 单副本门禁（P4 → Scale-4d）

Default `platform-app.yaml` keeps **`replicas: 1`**. That published default does not change. Do **not** advertise multi-replica unless both shared backends are `jdbc` (rate-limit + delivery circuit breaker). The optional honest patch is `platform-app-shared-backends.snippet.yaml` (`replicas: 2` **and** both env vars). A patch that only raises the replica count is dishonest and is rejected by `MultiReplicaRecipeHonestyTest`. These files are not applied by CI; checking them in is not a live deploy. Production object storage is S3-compatible (Secret placeholders in the manifest); local disk is not the multi-instance plan. Compose: [`deploy/compose/docker-compose.shared-backends.yml`](../compose/docker-compose.shared-backends.yml). Details: [`docs/release/single-replica-gate.md`](../../docs/release/single-replica-gate.md).

默认清单保持单副本，已发布的默认值不变。未同时打开双 `jdbc` 后端时不要对外宣称多副本。可选的诚实补丁是 `platform-app-shared-backends.snippet.yaml`（`replicas: 2` **且**两个环境变量都在）。只提高副本数的补丁不诚实，会被门禁测试拒绝。CI 不应用这些文件；写入仓库不是一次真实部署。生产对象存储为 S3 兼容（清单用 Secret 占位）；本地盘不是多实例方案。Compose 覆盖见上文。详见单副本门禁文档。

