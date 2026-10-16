# End-user clients / 终端客户端

| Path | Role |
| --- | --- |
| **`web/`** (repo root) | **Operator console** (Next.js) — ops / low-code authoring. Not C-end. |
| **`clients/uni-app/`** | **Product Web/H5 + 微信小程序** — uni-app Vue3 + Vite, HBuilderX-compatible |

原则见 [`docs/adr/0007-client-triad.md`](../docs/adr/0007-client-triad.md) 与根目录 [`AGENTS.md`](../AGENTS.md)。

- 后续**终端用户**业务 UI → `clients/uni-app`
- 不要把 C 端塞进 `web/`
- 未经新 ADR，不引入 Taro / React Native 作为第二套移动栈

Principle: see ADR 0007. End-user UI goes here; keep `web/` as console only.
