package com.subjex.platform.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Same naming guard (token match), scanning every module visible from this test classpath.
 * 同一条命名护栏（词段匹配），扫描本模块测试 classpath 上能看见的全部模块。
 * <p>
 * Matching is by CamelCase / package <em>tokens</em> (not raw substrings), so {@code Metadata}
 * and OIDC {@code Userinfo} are allowed while a segment named {@code Data} is not.
 * 按 CamelCase / 包路径<strong>词段</strong>匹配（非裸子串），故 Metadata、OIDC Userinfo 允许，独立词段 Data 不允许。
 * <p>
 * {@code package-info} is the Java package descriptor, not a business name, so it is exempt.
 * {@code package-info} 是 Java 的包说明文件名，不是业务名字，因此排除。
 */
@AnalyzeClasses(packages = "com.subjex", importOptions = ImportOption.DoNotIncludeTests.class)
class NamingGuardArchTest {

    private static final List<String> FORBIDDEN = List.of(
            "competition",
            "credential",
            "exam",
            "mall",
            "commerce",
            "activity",
            "data",
            "info",
            "tmp");

    @ArchTest
    static final ArchRule no_forbidden_fragments_in_class_or_package_names =
            classes()
                    .should(new ArchCondition<JavaClass>("not contain forbidden naming fragments") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            String className = javaClass.getSimpleName();
                            if ("package-info".equals(className)) {
                                return;
                            }
                            List<String> tokens = new ArrayList<>();
                            tokens.addAll(camelTokens(className));
                            for (String segment : javaClass.getPackageName().split("\\.")) {
                                tokens.addAll(camelTokens(segment));
                            }
                            for (String fragment : FORBIDDEN) {
                                for (String token : tokens) {
                                    if (token.equals(fragment)) {
                                        events.add(SimpleConditionEvent.violated(
                                                javaClass,
                                                javaClass.getName()
                                                        + " contains forbidden fragment '"
                                                        + fragment
                                                        + "'"));
                                        break;
                                    }
                                }
                            }
                        }
                    })
                    .because("business words and placeholder words must not become platform names "
                            + "/ 业务词和占位词不得变成平台名字");

    /** Split CamelCase / digits / underscores into lower-case tokens — 拆成小写词段。 */
    static List<String> camelTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] rough = raw.split("[_\\-]+");
        List<String> out = new ArrayList<>();
        for (String part : rough) {
            if (part.isEmpty()) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (i > 0 && Character.isUpperCase(c) && (Character.isLowerCase(part.charAt(i - 1))
                        || (i + 1 < part.length() && Character.isLowerCase(part.charAt(i + 1))))) {
                    out.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                current.append(c);
            }
            if (current.length() > 0) {
                out.add(current.toString().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
