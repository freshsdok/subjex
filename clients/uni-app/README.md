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
export VITE_SUBJEX_TENANT_ID=your-existing-tenant   # 业务租户，不能是 platform
```

默认 API 根为 `http://127.0.0.1:8080`（`platform-app`）。认证只走终端接口 `/api/v1/end-user/auth/**`（手机号注册/登录、小程序 `jsCode`、刷新/退出、绑定/解绑），令牌前缀为 `eu.at.` / `eu.rt.`。**不要**调用操作员 `/api/v1/auth/login` 或 `/api/v1/auth/idp/wechat/callback`，**不要**复用 `web/` 的 Redis 操作员会话。

租户必须显式给出（登录页输入、启动查询 `?tenantId=`，或上述环境变量），且必须是已存在的 ACTIVE 业务租户。客户端永不默认 `platform`，也不创建租户。

Tenant must be an explicit existing ACTIVE business tenant (login field, launch query `?tenantId=`, or env). The client never defaults to `platform` and never creates a tenant.


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

小程序登录：`uni.login` 取得 `jsCode` 后调用 `POST /api/v1/end-user/auth/wechat/login`（`{ jsCode, tenantId }`），首次可自动开户。H5 没有 `jsCode`，登录页隐藏微信登录，改用手机号。

Mini-program login uses `uni.login` → end-user WeChat API (auto-provision on first login). H5 hides WeChat login and uses phone. Do not commit a production appid/secret.

## HBuilderX

1. 安装 [HBuilderX](https://www.dcloud.io/hbuilderx.html)（建议正式版，带 uni-app 支持）。
2. **文件 → 导入 → 从本地目录导入**，选本目录 `clients/uni-app`（需已 `npm install`，或让 HBuilderX 安装依赖）。
3. 运行到浏览器 = H5；运行到小程序模拟器 = 微信开发者工具（需本机已装）。
4. 也可先用 npm 脚本构建，再用微信开发者工具打开 `dist/dev/mp-weixin`。

本工程是标准 Vite uni-app 结构，与 HBuilderX「Vue3 + Vite」项目兼容；若 HBuilderX 提示升级 uni 版本，与 `package.json` 中 `@dcloudio/*` 对齐即可。

## 问卷填写（Q7）

- 入口：`pages/questionnaire/list` — 粘贴采集令牌即可打开填写，**不强制登录**。
- 填写：`pages/questionnaire/fill?token=` — 公开 `GET/POST /api/v1/questionnaires/public/{token}`（ANON_LINK）。
- 已发布列表仍走操作员 `questionnaire.read`；终端令牌可能 403（WP2-5 残余）。认证终端用户提交 HTTP 仍属加深。

Hub + token fill for ANON_LINK does not require login; AUTH_END_USER submit HTTP is deepen.

## UI 权限 Manifest（PC-5）


壳页仍尝试 `GET /api/v1/ui-permissions?client=uni-app`。该接口在 WP2-5 后对终端令牌返回 403（操作员门）；壳会软失败并回退到本地导航，不阻断登录或匿名问卷填写。

The UI-permission API is operator-gated; the shell soft-fails and falls back to local nav.

## 非目标（首波脚手架）

- 不克隆操作员控制台菜单 / 组织构建器 / 声明编辑器
- 不接完整业务页
- 不引入 Taro / RN（见 ADR 0007）

## 相关文档

- [`docs/adr/0007-client-triad.md`](../../docs/adr/0007-client-triad.md)
- [`clients/README.md`](../README.md)
- WeChat IdP：[`docs/identity/id-progress.md`](../../docs/identity/id-progress.md)（协议在服务端；前端在本目录）
