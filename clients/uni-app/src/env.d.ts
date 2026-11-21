/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Platform API root, e.g. http://127.0.0.1:8080 / 平台 API 根地址 */
  readonly VITE_SUBJEX_API_BASE?: string;
  /** Optional default business tenant (never platform) / 可选默认业务租户（不能是 platform） */
  readonly VITE_SUBJEX_TENANT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<object, object, unknown>;
  export default component;
}
