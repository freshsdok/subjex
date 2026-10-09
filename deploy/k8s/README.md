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

platform-app in these manifests does not run the `local` profile, so no operator is seeded. Provision operator rows first (see `docs/operator-permissions.md`); sample-consumer signs in with `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD`.

清单里的 platform-app 不启用 `local` profile，因此不会写入操作员。请先开通操作员表行（见 `docs/operator-permissions.md`）；sample-consumer 用 `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD` 登录。

Outbox delivery (fail-closed): create Secret `outbox-delivery` (`hmac-secret`, `truststore-password` / `keystore-password`) and Secret `outbox-delivery-tls` (PKCS12 files mounted at `/var/run/outbox-tls`). `OUTBOX_TLS_ENABLED=true` is set in the manifests.
出箱投递（失败关闭）：创建 Secret `outbox-delivery` 与挂载 PKCS12 的 `outbox-delivery-tls`。清单已设 `OUTBOX_TLS_ENABLED=true`。

The human page is `GET /deploy` on platform-app. It reads these files. It does not apply them.

给人看的页面是 platform-app 上的 `GET /deploy`。它读这些文件，不应用它们。
---
entry-gateway.yaml — HTTP entry gateway (not applied by tests).

## Management port and NetworkPolicy / 管理端口与网络策略（P2）

Probes and Prometheus use `management.server.port` (not the business port). Sample isolation: `networkpolicy-sample.yaml`.
探针与 Prometheus 走管理端口（非业务口）。隔离样例见 `networkpolicy-sample.yaml`。
