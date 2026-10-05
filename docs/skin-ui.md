# Skin page / 外观页

Evidence opened before designing `GET /skin`. These are the pages that were read, not a theme store.

- CSS custom properties inherit and participate in the cascade. A page reads `var(--page-text)` and a selector on the document replaces the value. https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Cascading_variables/Using_custom_properties
- A small theme switch keeps a handful of named schemes as custom properties and selects one with a `data-theme` attribute. The choice is a name, not a color editor. https://mxb.dev/blog/color-theme-switcher/
- The miss this page is built against: every design token is a feature, and enough CSS variables (a line drawn near 500, worse when one variable only points at another) become a pile nobody can hold in their head. https://daverupert.com/2024/12/every-token-is-a-feature/

`GET /skin` is the opposite of that complaint. One sentence says these are three named skins, not a color picker. The page shows the current name and three links: plain, high contrast, and calm. Following a link sets a `skin` cookie, or the query selects it for that request. `/services`, `/config`, `/deploy`, `/forms`, `/codegen`, and `/language` use the selected skin. Each skin sets the same four variables: background, text, muted text, and line. The page does not list those variables.

`GET /skin` 对着的就是这个抱怨。一句话说明这是三个具名外观，不是取色器。页面写出当前名字，以及三个链接：朴素、高对比、沉静。点开链接会写入 `skin` cookie，查询参数也可以为这一次请求选中它。`/services`、`/config`、`/deploy`、`/forms`、`/codegen` 和 `/language` 使用选中的外观。每个外观只设置同样的四个变量：背景、文字、次要文字、分隔线。页面不把这些变量列出来。
