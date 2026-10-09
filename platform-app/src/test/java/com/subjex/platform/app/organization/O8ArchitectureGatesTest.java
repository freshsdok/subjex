package com.subjex.platform.app.organization;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * O8-6 ArchUnit rules: OrgUnit/OrgMembership legacy-only; forbidden ontology alias type names absent.
 */
@AnalyzeClasses(
        packages = "com.subjex.platform.app",
        importOptions = ImportOption.DoNotIncludeTests.class)
class O8ArchitectureGatesTest {

    @ArchTest
    static final ArchRule noOrgUnitTypesOutsideLegacy =
            noClasses()
                    .that()
                    .resideOutsideOfPackages(
                            "com.subjex.platform.app.org.legacy..",
                            "db.migration..")
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleName("OrgUnit")
                    .orShould()
                    .dependOnClassesThat()
                    .haveSimpleName("OrgMembership")
                    .because("OrgUnit/OrgMembership are legacy-only (O8-3/O8-6)");

    @ArchTest
    static final ArchRule noForbiddenOntologyAliases =
            noClasses()
                    .should()
                    .haveSimpleName("TenantMembership")
                    .orShould()
                    .haveSimpleName("TenantOrgUnit")
                    .orShould()
                    .haveSimpleName("OrganizationUnit")
                    .because("formal ontology forbids these type names");
}
