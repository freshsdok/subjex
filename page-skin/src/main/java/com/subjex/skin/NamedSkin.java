package com.subjex.skin;

/**
 * NamedSkin — 具名外观：一个名字对应一套能读的颜色，不是一组可编辑的变量。
 * <p>
 * A query token wins over a saved token. An unknown token is ignored. The default is plain.
 * 查询里的名字优先于已保存的名字。认不出的名字忽略。默认是朴素。
 */
public enum NamedSkin {

    /** Everyday page — 日常页面。 */
    PLAIN("plain", "朴素", "plain"),

    /** Black on white — 黑字白底。 */
    HIGH_CONTRAST("high-contrast", "高对比", "high contrast"),

    /** Quiet blue-gray — 安静的蓝灰。 */
    CALM("calm", "沉静", "calm");

    private final String token;
    private final String chinese;
    private final String english;

    NamedSkin(String token, String chinese, String english) {
        this.token = token;
        this.chinese = chinese;
        this.english = english;
    }

    /** Cookie and query value — cookie 和查询里的值。 */
    public String token() {
        return token;
    }

    /** Chinese name — 中文名。 */
    public String chinese() {
        return chinese;
    }

    /** English name — 英文名。 */
    public String english() {
        return english;
    }

    /**
     * Known token, or null — 认得出的名字，否则 null。
     */
    public static NamedSkin parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (NamedSkin skin : values()) {
            if (skin.token.equals(token)) {
                return skin;
            }
        }
        return null;
    }

    /**
     * Query, then saved choice, then plain.
     * 先查询，再已保存的选择，最后是朴素。
     */
    public static NamedSkin choose(String query, String saved) {
        NamedSkin fromQuery = parse(query);
        if (fromQuery != null) {
            return fromQuery;
        }
        NamedSkin fromSaved = parse(saved);
        if (fromSaved != null) {
            return fromSaved;
        }
        return PLAIN;
    }

    /**
     * One stylesheet for every operator page. The selected skin is the html data-skin attribute.
     * 所有操作页共用这一张样式表。选中的外观写在 html 的 data-skin 上。
     */
    public static String styleSheet() {
        return """
                html[data-skin="plain"] {
                  --page-background: #ffffff;
                  --page-text: #1c1c1c;
                  --page-muted: #555555;
                  --page-line: #dddddd;
                }
                html[data-skin="high-contrast"] {
                  --page-background: #ffffff;
                  --page-text: #000000;
                  --page-muted: #000000;
                  --page-line: #000000;
                }
                html[data-skin="calm"] {
                  --page-background: #e7eef2;
                  --page-text: #1a2a32;
                  --page-muted: #3d5560;
                  --page-line: #b7c6ce;
                }
                body {
                  font-family: system-ui, sans-serif;
                  margin: 2rem;
                  max-width: 52rem;
                  background: var(--page-background);
                  color: var(--page-text);
                  line-height: 1.4;
                }
                table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
                th, td {
                  text-align: left;
                  padding: 0.6rem 0.75rem;
                  border-bottom: 1px solid var(--page-line);
                  vertical-align: top;
                }
                .en { display: block; color: var(--page-muted); font-size: 0.85rem; font-weight: normal; }
                .intro { margin: 0; }
                h1 { font-size: 1.4rem; font-weight: 600; margin: 1.25rem 0 0; }
                code { font-size: 0.95rem; }
                a { color: var(--page-text); }
                ul { margin-top: 1rem; }
                """;
    }
}
