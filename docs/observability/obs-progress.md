# Observability progress — 可观测性进度

Wave after Identity MVP (ID-0..4). Prefer solid **OBS-1** + thin **OBS-2**.

身份 MVP 之后的可观测性波次。优先扎实 OBS-1 + 薄 OBS-2。

## Inventory (before OBS-1) / 盘点

| Piece | Status |
| --- | --- |
| `AuditPort` / `JdbcAuditPort` → `audit_entry` | Present since V1; RT-6 linkage columns |
| `OperatorActionAudit` | Single write path for operator actions |
| Console `/audit` + JSON `/api/v1/audit` | List existed; **no filters** before OBS-1 |
| OpenTelemetry SDK + OTLP/HTTP | `TracingConfiguration`; discard exporter unless `platform.tracing.otlp-endpoint` / `PLATFORM_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` |
| W3C `traceparent` | Outbox → sample-consumer inject; **HTTP extract missing** before OBS-2 |
| Micrometer / dashboards | Not in-platform (deferred) |

## OBS-1 [done] — audit query API + identity event coverage

- `GET /api/v1/audit?tenantId=&actor=&action=&from=&to=&limit=` — filters on `audit_entry` (action exact or prefix).
- Response: `{ entries, appliedFilters }`.
- Console `/audit` — same query params + minimal filter form.
- Identity audit actions covered: login / OIDC / MFA / IdP bind|unlink|self-link; `oauth.client.*`; `oauth.consent.approve|deny|revoke`; `oauth.token.client_credentials|authorization_code`.
- Tests: `AuditQueryApiTest`, identity regression suite.

## OBS-2 [thin done] — correlation + traceparent + MDC

- `RequestObservabilityFilter`: `X-Correlation-Id` / `X-Request-Id` (or mint) → response + MDC `correlationId`.
- Extract W3C `traceparent` via platform OTel propagator; SERVER span; MDC `traceId`; echo `traceparent` on response.
- Logging pattern includes `%X{correlationId}` / `%X{traceId}`.
- Docs: gateway should forward `traceparent` and optionally `X-Correlation-Id` unchanged.

## Deferred / 延后

- Full Micrometer metrics / Grafana dashboards
- Audit export / SIEM sink
- SA actor login join (CC actor is synthetic `sa:` identity id; filter by `actor` login won't match)
- Distributed sampling policies beyond always-on
- Gateway-side span enrichment beyond header propagation

## ADR

See [`docs/adr/0004-observability-audit-trace.md`](../adr/0004-observability-audit-trace.md).
