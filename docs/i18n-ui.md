# Language page / 语言页

Evidence opened before designing `GET /language`. These are the pages that were read, not a translation desk.

- Spring's `ApplicationContext` is a `MessageSource`. A bean named `messageSource`, usually a `ResourceBundleMessageSource`, loads `basename_lang.properties` and resolves a code for a `Locale`. https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html
- In Spring MVC the header resolver reads `Accept-Language`. A `LocaleChangeInterceptor` can also switch from a request parameter, but it stores that choice through a session or cookie resolver. This process keeps the operator gate stateless, so `?lang=zh` or `?lang=en` is read on the request itself, then the header, then Chinese. https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/localeresolver.html
- The miss this page is built against: a missing-key handler that is handed the key and a default value, but not the current language, so the dumped keys do not say which language failed. https://github.com/i18next/i18next/issues/1830

`GET /language` is the opposite of that complaint. One sentence says this is not a translation editor. Then the current language in words, 中文 or English. Then each catalog phrase in that language. The catalog is the operator page titles: services, config, deploy, forms, codegen, language, and skin. The codes stay off the page. There is no editor and no write.

`GET /language` 对着的就是这个抱怨。一句话说明这不是翻译编辑台。然后是当前语言，中文或 English。然后是该语言下目录里的每一句话。目录是操作页标题：服务、配置、部署、表单、生成、语言、外观。代号不出现在页面上。没有编辑器，也没有写操作。
