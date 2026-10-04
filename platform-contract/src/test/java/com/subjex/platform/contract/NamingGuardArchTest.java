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
 * Naming guard — 命名护栏：禁止业务敏感词出现在类名或包名中。
 * <p>
 * Fails the build when any class or package name contains a forbidden fragment.
 */
@AnalyzeClasses(packages = "com.subjex", importOptions = ImportOption.DoNotIncludeTests.class)
class NamingGuardArchTest {

    private static final List<String> FORBIDDEN = List.of(
            "competition",
            "credential",
            "exam",
            "mall",
            "commerce",
            "activity");

    @ArchTest
    static final ArchRule no_forbidden_fragments_in_class_or_package_names =
            classes()
                    .should(new ArchCondition<JavaClass>("not contain forbidden naming fragments") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            String className = javaClass.getSimpleName().toLowerCase(Locale.ROOT);
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
                    .because("business domain words must not leak into platform skeleton names "
                            + "/ 业务敏感词不得进入平台骨架命名");
}
