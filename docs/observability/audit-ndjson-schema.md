# Audit NDJSON schema / 审计 NDJSON 模式（WP9-1）

Stable field names for `POST /api/v1/audit/export` and the optional file/stdout `AuditSink`.
Records contain **no secrets or tokens** (no passwords, recovery codes, signing keys, or bearer values).
`actorIdentityId` is an identity id; `actorLogin` is a login name when one can be joined, otherwise null/omitted on the write-path sink.

`POST /api/v1/audit/export` 与可选文件/标准输出 `AuditSink` 的稳定字段名。
记录**不含密钥或令牌**（无口令、恢复码、签名密钥或 Bearer）。
`actorIdentityId` 是身份标识；`actorLogin` 在能关联到登录名时给出，写路径 sink 上可能为 null。

Content type: `application/x-ndjson`. One JSON object per line.

| Field | Type | Meaning / 含义 |
| --- | --- | --- |
| `auditEntryId` | string | Row id / 行标识 |
| `occurredAt` | string (ISO-8601 instant) | When the action was recorded / 记录时间 |
| `tenantId` | string | Tenant of the fact / 该事实所属租户 |
| `actorIdentityId` | string | Acting identity id (not a secret) / 操作者身份标识（不是密钥） |
| `actorLogin` | string or null | Joined login name, if any / 关联到的登录名（可空） |
| `actionName` | string | Action token, e.g. `operator.auth.login` / 动作名 |
| `actionTarget` | string or null | What was touched / 动作对象 |
| `outcome` | string | `ALLOWED` / `REFUSED` / `FAILED` |
| `entityKey` | string or null | Optional declaration entity key / 可选声明实体键 |
| `declarationVersion` | number or null | Optional declaration version / 可选声明版本 |
| `resolutionSource` | string or null | Optional resolution source / 可选解析来源 |

Export request: `POST /api/v1/audit/export?tenantId=&from=&to=&limit=` (ISO-8601 `from` / `to`). Requires `audit.export` and a grant on that tenant. Reserved tenant `platform` is denied unless the operator is granted it. Window cap **P31D**, row cap default 1000 / max 5000. The export itself is recorded as `audit.export`.

导出要求 `audit.export` 及对该租户的授权。无 `platform` 授权则拒绝导出保留租户。时间窗上限 31 天，条数默认 1000、最大 5000。导出动作本身记为 `audit.export`。

The optional sink is off by default (`platform.audit.sink.enabled=false`). When enabled, each `AuditPort.record` also appends one NDJSON line to stdout or a file (`platform.audit.sink.type=stdout|file`, `platform.audit.sink.file=...`). This is a **write-path fan-out**, not a second outbox table.

可选 sink 默认关闭。启用后，每次 `AuditPort.record` 向标准输出或文件追加一行。这是**写路径扇出**，不是第二张出箱表。

## Shipping NDJSON to a SIEM / 把 NDJSON 送到 SIEM

A collector such as Vector or Fluent Bit can tail the sink file (or read stdout of a sidecar) and forward lines to a SIEM over HTTP, syslog, or the collector’s native sink. Keep management ports and `/actuator/**` off the public edge (see [`deploy/edge/README.md`](../../deploy/edge/README.md)). Do not add a vendor SDK to the kernel.

可用 Vector 或 Fluent Bit 等采集器跟踪 sink 文件（或读 sidecar 的标准输出），再经 HTTP、syslog 或采集器自带出口转发到 SIEM。管理口与 `/actuator/**` 不对公网开放。不要在内核加入厂商 SDK。

Example (Vector, illustrative only) / 示例（Vector，仅示意）:

```toml
[sources.subjex_audit]
type = "file"
include = ["/var/log/subjex/audit.ndjson"]

[sinks.siem]
type = "http"
inputs = ["subjex_audit"]
uri = "https://siem.example.com/logs"
encoding.codec = "text"
```
