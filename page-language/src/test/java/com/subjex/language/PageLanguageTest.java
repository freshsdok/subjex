package com.subjex.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class PageLanguageTest {

    @Test
    void queryWinsThenHeaderThenChinese() {
        assertEquals(PageLanguage.CHINESE, PageLanguage.resolve(null, null));
        assertEquals(PageLanguage.CHINESE, PageLanguage.resolve("", ""));
        assertEquals(PageLanguage.CHINESE, PageLanguage.resolve("zh", "en"));
        assertEquals(PageLanguage.ENGLISH, PageLanguage.resolve("en", "zh-CN"));
        assertEquals(PageLanguage.ENGLISH, PageLanguage.resolve("fr", "en-US,en;q=0.8"));
        assertEquals(PageLanguage.CHINESE, PageLanguage.resolve("fr", "zh-CN,en;q=0.5"));
        assertEquals(PageLanguage.CHINESE, PageLanguage.resolve(null, "fr"));
        assertEquals(PageLanguage.CHINESE, PageLanguage.resolve("EN", "not a header <<<"));
        assertEquals("中文", PageLanguage.name(PageLanguage.CHINESE));
        assertEquals("English", PageLanguage.name(PageLanguage.ENGLISH));
    }

    @Test
    void bothLanguagesHaveTheSameTitlesInPlainWords() {
        ResourceBundle.Control control = ResourceBundle.Control.getNoFallbackControl(
                ResourceBundle.Control.FORMAT_PROPERTIES);
        ResourceBundle chinese = ResourceBundle.getBundle("pagephrases", PageLanguage.CHINESE, control);
        ResourceBundle english = ResourceBundle.getBundle("pagephrases", PageLanguage.ENGLISH, control);
        assertEquals(PageTitleCatalog.CODES.size(), chinese.keySet().size());
        for (String code : PageTitleCatalog.CODES) {
            String zh = chinese.getString(code);
            String en = english.getString(code);
            assertNotEquals(zh, en, code);
            assertEquals(zh, zh.trim());
            assertEquals(en, en.trim());
        }
        assertEquals("服务名单", chinese.getString(PageTitleCatalog.SERVICES));
        assertEquals("配置名单", chinese.getString(PageTitleCatalog.CONFIG));
        assertEquals("部署清单", chinese.getString(PageTitleCatalog.DEPLOY));
        assertEquals("字段列表", chinese.getString(PageTitleCatalog.FORMS));
        assertEquals("生成类型", chinese.getString(PageTitleCatalog.CODEGEN));
        assertEquals("Service list", english.getString(PageTitleCatalog.SERVICES));
        assertEquals("Config list", english.getString(PageTitleCatalog.CONFIG));
        assertEquals("Deploy manifests", english.getString(PageTitleCatalog.DEPLOY));
        assertEquals("Field list", english.getString(PageTitleCatalog.FORMS));
        assertEquals("Generated type", english.getString(PageTitleCatalog.CODEGEN));
        assertEquals("Language", english.getString(PageTitleCatalog.LANGUAGE));
        assertEquals("Skin", english.getString(PageTitleCatalog.SKIN));
    }
}
