# Node 5 progress — 节点五进度

Slice log — 切片记录:

5a. [done] ARCHITECTURE §14: Prometheus, optional OTLP, two platform-app replicas, smoke load, Redis sessions (TD-1).
5b. [done] Micrometer Prometheus registry + `/actuator/prometheus` (permitAll) on platform-app, entry-gateway, sample-consumer; ProbeConfigurationTest / GatewayMetricsConfigurationTest.
5c. [done] Optional OTLP/HTTP when `platform.tracing.otlp-endpoint` / `PLATFORM_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` is set; else DiscardingSpanExporter. TracingExporterChoiceTest.
5d. [done] Compose dual platform-app (`--scale platform-app=2`, no host 8080) + Redis; k8s `replicas: 2`; `deploy/load/smoke-load.sh`.
5e. [done] Web operator sessions → Redis when `SESSION_REDIS_URL` / `REDIS_URL` set; memory fallback for tests/single-node; TD-1 marked done in design-notes.
5f. [next] Push to GitHub.
