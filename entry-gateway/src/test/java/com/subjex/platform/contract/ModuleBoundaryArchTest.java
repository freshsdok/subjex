package com.subjex.platform.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module boundary — 模块边界：契约不依赖宿主、示例或网关，网关不依赖宿主，读写不经过模型层。
 * <p>
 * This is the only architecture gate added for boundaries already written in ARCHITECTURE.md.
 * 这是唯一一条为 ARCHITECTURE.md 已写明的边界增加的架构门禁。
 * <p>
 * Only rules whose {@code that()} clause matches classes on this module's test classpath live here
 * (contract and gateway). Host/sample rules ({@code host_does_not_depend_on_the_sample},
 * {@code sample_does_not_depend_on_the_host}, {@code host_does_not_depend_on_the_gateway}) live in
 * {@code sample-consumer}, whose test classpath carries both host and sample classes; ArchUnit's
 * {@code failOnEmptyShould} would fail them here.
 * 只保留在本模块测试 classpath 上能匹配到类的规则；宿主/示例相关规则放在 sample-consumer。
 */
@AnalyzeClasses(packages = "com.subjex", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

    @ArchTest
    static final ArchRule contract_stays_free_of_the_host_and_the_sample =
            noClasses()
                    .that().resideInAPackage("com.subjex.platform.contract..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.subjex.platform.app..",
                            "com.subjex.sample..",
                            "com.subjex.gateway..");

    @ArchTest
    static final ArchRule gateway_does_not_depend_on_the_host =
            noClasses()
                    .that().resideInAPackage("com.subjex.gateway..")
                    .should().dependOnClassesThat().resideInAPackage("com.subjex.platform.app..");

    @ArchTest
    static final ArchRule reads_and_writes_do_not_use_a_model_layer =
            noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "org.springframework.data..");
}
