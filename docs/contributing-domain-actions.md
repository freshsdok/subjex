# Contributing domain actions / 贡献领域动作

How to reuse or add a form `domainAction` without guessing the call path.
如何复用或新增表单 `domainAction`，不必猜调用链。

Related / 相关：[`docs/auth-default-matrix.md`](auth-default-matrix.md) · `form-render` `DomainActionKey` · `platform-app` `DomainActionHandler`

---

## Reuse an existing action (YAML only) / 复用已有动作（只改 YAML）

If the write path you need is already one of:

若所需写入路径已是下列之一：

| Key | Purpose |
| --- | --- |
| `registry.register` | Register a service endpoint |
| `config.override` | Write a config override |
| `entity.record.upsert` | Upsert entity record (`entityKey` on the form) |
| `capability.algo.hashFingerprint` / `normalizeWhitespace` | Deterministic capability stubs |
| `capability.ai.summarizePreview` / `suggestTitlePreview` | AI preview stubs (no store write) |

Then:

1. Add or edit a form under `**/forms/*.form.yaml` with `domainAction: <key>`, `permission`, fields matching the action catalog (`actions/domain-action-catalog.yaml`).
2. Set `entityKey` when using `entity.record.upsert`.
3. No Java change. Rebuild / restart `platform-app`.

然后：改 YAML 声明即可，**不必改 Java**。

---

## Add a new action / 新增动作

Fail-closed stack: enum + YAML catalog + one handler bean.

失败关闭三件套：枚举 + YAML 目录 + 一个处理器 Bean。

1. **Enum** — add a value to `form-render` `DomainActionKey` (catalog string).
2. **Catalog YAML** — add the same key + required/optional fields in `actions/domain-action-catalog.yaml` (loaded by `DomainActionCatalog`).
3. **Handler** — implement `com.subjex.platform.app.form.DomainActionHandler` in `platform-app`:
   - Prefer `@Component` (constructor-injected ports), **or** a `@Bean` in `DomainActionHandlerConfiguration` for thin wrappers.
   - `key()` must return the new enum value.
   - `apply(...)` performs the write; return a short summary string for the submission response.
4. **Permission** — declare `permission` on the form YAML; add a named permission + role grant if the catalog does not already have one (see `docs/operator-permissions.md`).
5. **Tests** — extend `FormDomainActionRunnerTest` / catalog tests; `FormDomainActionRunner` **rejects startup** if any enum key lacks a handler or if two handlers claim the same key.

Do **not** edit a central switch in `FormDomainActionRunner` — it only dispatches the handler map.

**不要**再改 `FormDomainActionRunner` 里的大 switch；它只做处理器映射分发。

---

## Wiring / 装配

- Production: Spring injects `List<DomainActionHandler>` into `FormDomainActionRunner`.
- Tests without Spring: `new FormDomainActionRunner(services, config, …)` installs the same built-in handlers.
