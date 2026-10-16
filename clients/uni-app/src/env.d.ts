/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Platform API root, e.g. http://127.0.0.1:8080 / 平台 API 根地址 */
  readonly VITE_SUBJEX_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<object, object, unknown>;
  export default component;
}
