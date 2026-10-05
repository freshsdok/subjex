package com.subjex.platform.app.language;

import com.subjex.language.PageLanguage;
import com.subjex.platform.app.api.JsonApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LanguageApiEndpoint — 语言接口：{@link LanguagePage} 的 JSON 孪生，可用语言及其目录里的每一句话。
 * <p>
 * Phrases are read from the {@code page-language} module's {@code pagephrases_<code>.properties} on the classpath,
 * read as UTF-8. {@code code} is the language tag, e.g. {@code zh} or {@code en}; {@code phrases} maps each catalog
 * key (e.g. {@code title.deploy}) to its text. Unlike the page, the JSON carries the keys so a client can look them up.
 * 话从 classpath 上 {@code page-language} 模块的 {@code pagephrases_<代号>.properties} 读出，按 UTF-8 读。
 * {@code code} 是语言标签，例如 {@code zh} 或 {@code en}；{@code phrases} 把每个目录键（例如 {@code title.deploy}）
 * 对应到它的文字。和页面不同，JSON 带上键，方便客户端查找。
 */
@RestController
public class LanguageApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/language";

    /** Bundle base name — 资源包基名。 */
    static final String BUNDLE = "pagephrases";

    /** Available languages, in reading order — 可用语言，按阅读顺序。 */
    static final List<Locale> LANGUAGES = List.of(PageLanguage.CHINESE, PageLanguage.ENGLISH);

    @GetMapping(PATH)
    public LanguageListDocument languages() {
        List<LanguageDocument> languages = new ArrayList<>();
        for (Locale locale : LANGUAGES) {
            String code = locale.toLanguageTag();
            languages.add(new LanguageDocument(code, phrases(code)));
        }
        return new LanguageListDocument(List.copyOf(languages));
    }

    /**
     * Key-to-text map for one language, keys sorted — 一种语言的键到文字映射，键已排序。
     */
    static Map<String, String> phrases(String code) {
        String resource = "/" + BUNDLE + "_" + code + ".properties";
        try (InputStream in = LanguageApiEndpoint.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            Properties loaded = new Properties();
            loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> phrases = new LinkedHashMap<>();
            for (String key : new TreeSet<>(loaded.stringPropertyNames())) {
                phrases.put(key, loaded.getProperty(key));
            }
            return phrases;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * LanguageListDocument — 语言文档：可用的语言。
     */
    public record LanguageListDocument(List<LanguageDocument> languages) {}

    /**
     * LanguageDocument — 一种语言：语言标签（zh / en），以及目录键到这句话的映射。
     */
    public record LanguageDocument(String code, Map<String, String> phrases) {}
}
