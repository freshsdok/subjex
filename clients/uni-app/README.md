# subjex uni-app — H5 + 微信小程序壳

Vue 3 + Vite + `@dcloudio/vite-plugin-uni`。**终端用户**壳，不是操作员控制台（控制台在仓库根 `web/`）。

End-user shell (H5 + WeChat mini-program). Operator console remains `web/`.

## npm（推荐本地先跑 H5）

```bash
cd clients/uni-app
npm install
npm run dev:h5      # http://localhost:5173 （端口以终端输出为准）
npm run build:h5    # 产出 dist/build/h5
```

环境变量（可选）：

```bash
export VITE_SUBJEX_API_BASE=http://127.0.0.1:8080
```

默认 API 根为 `http://127.0.0.1:8080`（`platform-app`）。认证应为 **Bearer / OP**；**不要**复用 `web/` 的 Redis 操作员会话。


## CI

GitHub Actions job **`clients-uni-app`** (`.github/workflows/build.yml`) runs `npm ci` + `npm run build:h5` on every push/PR.
**`build:mp-weixin` is not in CI** — use WeChat DevTools or HBuilderX locally.

CI 任务 `clients-uni-app` 只构建 H5；微信小程序构建不在 CI 中跑。

## 微信小程序

```bash
npm run dev:mp-weixin    # 产出 dist/dev/mp-weixin
npm run build:mp-weixin  # 产出 dist/build/mp-weixin
```

用[微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html)打开对应目录。`src/manifest.json` → `mp-weixin.appid` 先留空；本地可用测试号 / tourist 流程（在开发者工具里配置），**不要把正式 appid/secret 提交进仓**。

服务端已有 IdP：`wechat:mini` + `jsCode` → `jscode2session`（`platform.idp.wechat.*`）。本壳登录页只做占位说明，不提交密钥。

## HBuilderX

1. 安装 [HBuilderX](https://www.dcloud.io/hbuilderx.html)（建议正式版，带 uni-app 支持）。
2. **文件 → 导入 → 从本地目录导入**，选本目录 `clients/uni-app`（需已 `npm install`，或让 HBuilderX 安装依赖）。
3. 运行到浏览器 = H5；运行到小程序模拟器 = 微信开发者工具（需本机已装）。
4. 也可先用 npm 脚本构建，再用微信开发者工具打开 `dist/dev/mp-weixin`。

本工程是标准 Vite uni-app 结构，与 HBuilderX「Vue3 + Vite」项目兼容；若 HBuilderX 提示升级 uni 版本，与 `package.json` 中 `@dcloudio/*` 对齐即可。

## 非目标（首波脚手架）

- 不克隆操作员控制台菜单 / 组织构建器 / 声明编辑器
- 不接完整业务页
- 不引入 Taro / RN（见 ADR 0007）

## 相关文档

- [`docs/adr/0007-client-triad.md`](../../docs/adr/0007-client-triad.md)
- [`clients/README.md`](../README.md)
- WeChat IdP：[`docs/identity/id-progress.md`](../../docs/identity/id-progress.md)（协议在服务端；前端在本目录）
