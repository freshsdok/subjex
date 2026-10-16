package com.subjex.platform.app.tenant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * TenantIsolationArchTest — TN-3: RestControllers that depend on {@link OperatorTenantAccess} must
 * call {@code requireGranted}. Documented exempt (do not inject the port): TaskSubmissionEndpoint
 * (TenantEnforcementFilter); TenantQuotaEndpoint / TenantDataLifecycleEndpoint (role
 * {@code tenant.manage} / {@code admin.read} + service checks).
 * 依赖 OperatorTenantAccess 的 RestController 必须调用 requireGranted；豁免见上。
 *
 * <p>Uses {@code @Test} (not {@code @ArchTest}) so Surefire {@code -Dgroups=tenant-isolation} picks
 * it up reliably with the class-level {@link Tag}.
 */
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class TenantIsolationArchTest {

    private static final DescribedPredicate<JavaClass> DEPEND_ON_OPERATOR_TENANT_ACCESS =
            DescribedPredicate.describe(
                    "depend on OperatorTenantAccess",
                    javaClass ->
                            javaClass.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(
                                            dep ->
                                                    dep.getTargetClass()
                                                            .isAssignableTo(OperatorTenantAccess.class)));

    static final ArchRule API_CONTROLLERS_WITH_TENANT_ACCESS_CALL_REQUIRE_GRANTED =
            classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .and()
                    .resideInAPackage("com.subjex.platform.app..")
                    .and(DEPEND_ON_OPERATOR_TENANT_ACCESS)
                    .should()
                    .callMethod(
                            OperatorTenantAccess.class,
                            "requireGranted",
                            OperatorPrincipal.class,
                            String.class)
                    .because(
                            "TN-3: /api/v1 tenant-data controllers that inject OperatorTenantAccess must call "
                                    + "requireGranted. Exempt (no port): TaskSubmissionEndpoint (filter); "
                                    + "TenantQuotaEndpoint / TenantDataLifecycleEndpoint (role + service).");

    @Test
    void apiControllersWithTenantAccessCallRequireGranted() {
        JavaClasses imported =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.subjex.platform.app");
        API_CONTROLLERS_WITH_TENANT_ACCESS_CALL_REQUIRE_GRANTED.check(imported);
    }
}
