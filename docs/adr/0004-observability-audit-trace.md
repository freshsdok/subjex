# ADR 0004 — Audit query + request tracing (OBS)

**Status:** Accepted (OBS-1 + thin OBS-2)  
**Date:** 2026-10-15 (Asia/Shanghai)

## Context

Identity MVP emits more security-sensitive events (login, consent, token issue, IdP link). Operators need to **query** `audit_entry` without SQL, and HTTP paths need a stable **correlation id** plus W3C **traceparent** so logs and outbox delivery share one digraph.

## Decision

1. **OBS-1:** Extend existing `/api/v1/audit` (and `/audit` HTML) with optional filters: tenant, actor login, action (exact or prefix), time window, limit. Keep a single `AuditPort` write path; enrich identity endpoints to `OperatorActionAudit.record` for consent and token grants.
2. **OBS-2 (thin):** One servlet filter extracts/mints correlation id, extracts `traceparent` into an OpenTelemetry SERVER span, writes `correlationId`/`traceId` to MDC, echoes headers. OTLP export stays opt-in via existing `platform.tracing.otlp-endpoint`.
3. **Non-goals now:** Micrometer dashboards, SIEM export, changing the audit schema.

## Consequences

- Operators can filter identity actions (`oauth.consent`, `operator.auth`, …) from console or API.
- Gateways must forward `traceparent` (and may forward `X-Correlation-Id`) for end-to-end traces.
- Service-account token audits store a synthetic actor identity id (`sa:…`); login-name filter may not join them.
