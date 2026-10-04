package com.subjex.platform.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module boundary — 模块边界：契约不依赖宿主或示例，宿主与示例互不依赖，读写不经过模型层。
 * <p>
 * This is the only architecture gate added for boundaries already written in ARCHITECTURE.md.
 * 这是唯一一条为 ARCHITECTURE.md 已写明的边界增加的架构门禁。
 */
@AnalyzeClasses(packages = "com.subjex", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

    @ArchTest
    static final ArchRule contract_stays_free_of_the_host_and_the_sample =
            noClasses()
                    .that().resideInAPackage("com.subjex.platform.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.subjex.platform.app..",
                            "com.subjex.sample..");

    @ArchTest
    static final ArchRule host_does_not_depend_on_the_sample =
            noClasses()
                    .that().resideInAPackage("com.subjex.platform.app..")
                    .should().dependOnClassesThat().resideInAPackage("com.subjex.sample..");

    @ArchTest
    static final ArchRule sample_does_not_depend_on_the_host =
            noClasses()
                    .that().resideInAPackage("com.subjex.sample..")
                    .should().dependOnClassesThat().resideInAPackage("com.subjex.platform.app..");

    @ArchTest
    static final ArchRule reads_and_writes_do_not_use_a_model_layer =
            noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "org.springframework.data..");
}
