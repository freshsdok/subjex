# Secrets rotation / 密钥轮换（P3）

Rotation does **not** require rebuilding the database. Each secret supports a dual-version overlap window.
轮换**不要求**重建库。每项支持双版本重叠期。

## Inventory / 清单

| Env | Used by | Generate | Overlap | Retire old |
| --- | --- | --- | --- | --- |
| `OUTBOX_HMAC_SECRET` | platform-app → consumer (socket HMAC) | `openssl rand -base64 48` (≥32 chars) | Set `OUTBOX_HMAC_SECRET_PREVIOUS` to the old value; publishers sign with the new secret; consumers accept either | Remove `*_PREVIOUS` after all in-flight frames age out (>5 min skew) |
| `OPERATOR_SESSION_SECRET` | `web/` session AES-GCM | `openssl rand -base64 48` (≥32) | `OPERATOR_SESSION_SECRET_PREVIOUS`; encrypt with new; decrypt tries new then old | Drop previous after sessions expire (default 8h) or force re-login |
| `PLATFORM_MFA_ENCRYPTION_KEY` | TOTP seed at rest | `openssl rand -base64 48` | `PLATFORM_MFA_ENCRYPTION_KEY_PREVIOUS`; encrypt with new; decrypt tries both | Drop previous after every enrolled operator has re-saved or you accept ephemeral re-enroll |

## Fail-closed placeholders / 占位符失败关闭

Outside Spring profile `local` (Java) / outside `NODE_ENV=development` (web), values containing `change-me` / `changeme` **refuse to start**.
非 `local`（Java）/ 非 development（web）时，含 `change-me` 的值**拒绝启动**。

Laptop defaults in `application-local.yml` remain valid only with `SPRING_PROFILES_ACTIVE=local`.
`application-local.yml` 中的本机默认只在 `local` profile 下合法。

## Procedure sketch / 步骤概要

1. Generate the new secret; keep the old value.
2. Deploy with **new as primary** and **old as `*_PREVIOUS`**.
3. Confirm traffic / login / MFA verify succeed.
4. After overlap (frames/sessions aged out), remove `*_PREVIOUS` and redeploy.

Do not rotate by emptying the env var — that fails closed or falls back to ephemeral keys that cannot decrypt existing ciphertext.
不要通过清空环境变量轮换——会失败关闭，或落到无法解密旧密文的临时密钥。
