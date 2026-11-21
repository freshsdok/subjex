export {}

declare module "vue" {
  type Hooks = App.AppInstance & Page.PageInstance;
  interface ComponentCustomOptions extends Hooks {}

  /**
   * Treat uni-app {@code <button>} as a component (not an HTML button),
   * so {@code type="primary"|"default"|"warn"} type-checks.
   * 把 uni 的 button 当作组件而非 HTML 按钮，从而允许 type="primary"。
   */
  interface GlobalComponents {
    button: import("vue").DefineComponent<{
      type?: "primary" | "default" | "warn";
      size?: "default" | "mini";
      disabled?: boolean;
      loading?: boolean;
      plain?: boolean;
      formType?: "submit" | "reset" | "button";
      openType?: string;
    }>;
  }
}
