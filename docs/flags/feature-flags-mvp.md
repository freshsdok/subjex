# Feature flags MVP / 功能开关 MVP

Date: 2026-10-16. Local after search read path.

## Approach

Reuse **config-center** namespace `flags` (`ConfigNamespaces.FLAGS`) — no new Flyway table.

| Piece | Role |
| --- | --- |
| `FeatureFlagService.isEnabled(tenantId, key, default)` | Runtime resolve: `tenantId/key` → platform `key` → default |
| `GET/PUT /api/v1/flags` | List/get/put; AuthZ `config.read` / `config.write` |
| Console `/flags` | Tenant list + enable/disable + add |
| Client `lib/feature-flags.ts` | Parse helpers only (server is authority) |

## Gated path (example)

- Flag key: `search.records` (`FeatureFlagKeys.SEARCH_RECORDS`)
- `GET /api/v1/search/records` returns **404** when disabled for the tenant (default **true**)

## API

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/flags?tenantId=` | Keys under `tenantId/` |
| GET | `/api/v1/flags/{flagKey}?tenantId=` | Storage `tenantId/flagKey` |
| PUT | `/api/v1/flags/{flagKey}?tenantId=` | Body `{ "value": "true"\|"false" }` |

Response fields: `key` (storage), `flagKey` (logical), `value`, `enabled`, `revision`, `origin`.

## Non-goals / Later

- Percentage rollouts, dependencies, kill-switches catalog UI beyond this page
- SDK annotation / AOP gating
- Separate `feature_flag` table
