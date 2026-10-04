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
import java.util.List;
import java.util.Locale;

/**
 * Naming guard — 命名护栏：类名和包名不得带竞赛词，也不得用 data、info、tmp 当名字。
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
                            String className = javaClass.getSimpleName().toLowerCase(Locale.ROOT);
                            if ("package-info".equals(className)) {
                                return;
                            }
                            String packageName = javaClass.getPackageName().toLowerCase(Locale.ROOT);
                            for (String fragment : FORBIDDEN) {
                                if (className.contains(fragment) || packageName.contains(fragment)) {
                                    events.add(SimpleConditionEvent.violated(
                                            javaClass,
                                            javaClass.getName() + " contains forbidden fragment '" + fragment + "'"));
                                }
                            }
                        }
                    })
                    .because("business words and placeholder words must not become platform names "
                            + "/ 业务词和占位词不得变成平台名字");
}
