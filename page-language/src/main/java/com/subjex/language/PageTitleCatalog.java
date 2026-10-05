package com.subjex.language;

import java.util.List;

/**
 * PageTitleCatalog — 页面标题目录：操作页标题的稳定代号，顺序就是语言页的阅读顺序。
 * <p>
 * The codes stay in this catalog. A page shows the phrase for the current language, not the code.
 * 代号留在这份目录里。页面展示的是当前语言的那句话，不是代号。
 */
public final class PageTitleCatalog {

    /** Service list title — 服务名单标题。 */
    public static final String SERVICES = "title.services";

    /** Config list title — 配置名单标题。 */
    public static final String CONFIG = "title.config";

    /** Deploy manifest title — 部署清单标题。 */
    public static final String DEPLOY = "title.deploy";

    /** Field list title — 字段列表标题。 */
    public static final String FORMS = "title.forms";

    /** Generated type title — 生成类型标题。 */
    public static final String CODEGEN = "title.codegen";

    /** Language page title — 语言页标题。 */
    public static final String LANGUAGE = "title.language";

    /** Skin page title — 外观页标题。 */
    public static final String SKIN = "title.skin";

    /** Audit page title — 审计记录标题。 */
    public static final String AUDIT = "title.audit";

    /** Reading order — 阅读顺序。 */
    public static final List<String> CODES = List.of(
            SERVICES, CONFIG, DEPLOY, FORMS, CODEGEN, LANGUAGE, SKIN, AUDIT);

    private PageTitleCatalog() {}
}
