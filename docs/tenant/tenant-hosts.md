# Tenant custom hosts — 租户独立域名

First cut (2026-11-23, Asia/Shanghai). Bind a unique hostname to a business tenant and resolve incoming `Host` so platform APIs (and the end-user shell when it shares that host) can identify the tenant.

首切口：把唯一主机名绑到业务租户，并按请求 `Host` 识别租户。

## What this cut does / 本切口做到的

- Persist bindings in `tenant_host` (Flyway V60). Names are unique, lower-cased, and stored without a port, scheme, or path.
- Operator console on `/tenants`: list / add / remove. List is `admin.read`; changes need `tenant.manage`.
- Request filter: a **bound, active** Host fills `X-Tenant-Id`. A conflicting header or several distinct Host values fail closed. A bound host whose tenant is missing or `SUSPENDED` is refused.
- Public `GET /api/v1/public/host-tenant` reports whether the **request** Host is bound. It does not list other tenants.

## Honest limits / 诚实边界

- **No DNS or certificates** are created. Binding a name here does not buy a domain or issue TLS. Edge recipes still use placeholder hostnames and placeholder cert paths.
- **`Host` only.** This cut does **not** read `X-Forwarded-Host` or `Forwarded`, because platform-app has no trusted-proxy list for those headers. Put the original public hostname in `Host` (the Nginx include already has `proxy_set_header Host $host`).
- **Operator console stays on the platform host.** Tenant domains are for tenant-scoped end-user / public entry. Add the console and API public names to `PLATFORM_RESERVED_HOSTS` so they are never treated as a tenant domain. Loopback (`localhost`, `127.0.0.1`, `::1`) is always reserved.
- **Unknown hosts do not invent a tenant.** An unbound hostname with an explicit `X-Tenant-Id` still works as it did before. The discovery API reports `bound: false`.
- **Page host vs API host.** If the end-user H5 is served on the tenant domain but the API is called on a different reserved platform host, the API request Host is the API host. The browser cannot set `Host`. In that setup, keep sending the tenant id as today.
- The stock Nginx `server_name` is still a placeholder (`api.example.com`). To accept tenant domains at the edge you must extend `server_name` (or add a catch-all) and install real certificates — not in this repository.

## Config / 配置

```yaml
platform:
  hosts:
    reserved: ${PLATFORM_RESERVED_HOSTS:localhost,127.0.0.1,::1}
```
