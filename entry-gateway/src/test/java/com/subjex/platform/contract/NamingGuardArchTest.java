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
 * Same naming guard, scanning every module visible from the consumer test classpath.
 * 同一条命名护栏，扫描消费者测试 classpath 上能看见的全部模块。
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
