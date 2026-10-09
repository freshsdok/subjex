# Probes and network policy / 探针与网络策略（P2）

## Ports / 端口

| Process | Business port | Management port (`MANAGEMENT_SERVER_PORT`) |
| --- | --- | --- |
| platform-app | 8080 | 8089 |
| entry-gateway | 8088 | 8090 |
| sample-consumer | 8081 (+ delivery 19081) | 8091 |

- **Liveness / readiness** stay anonymous on the **management** port for kubelet. Restrict to cluster CIDRs with NetworkPolicy — do not publish them on Ingress.
  存活/就绪在**管理端口**匿名供 kubelet；用 NetworkPolicy 限制集群网段，勿经 Ingress 暴露。
- **`/actuator/prometheus`** is only on the management port — not alongside the business API port.
  指标只在管理端口，不与业务口同挂。

## Sample policy / 样例策略

See `deploy/k8s/networkpolicy-sample.yaml`:

1. Business HTTP → only `entry-gateway` may reach `platform-app:8080`.
2. Outbox → only `platform-app` may reach `sample-consumer:19081`.
3. Probes/metrics → cluster CIDR to management ports; not via Ingress.

Replace `10.0.0.0/8` with your pod/node CIDRs before apply.
应用前把 `10.0.0.0/8` 换成真实 pod/node CIDR。
