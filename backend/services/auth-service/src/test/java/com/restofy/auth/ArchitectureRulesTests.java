package com.restofy.auth;

import com.restofy.auth.infrastructure.config.EnvironmentPolicy;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.core.env.Environment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packagesOf = AuthServiceApplication.class, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTests {
    /**
     * Relaxations that must never reach production (plain-HTTP Mail address, development users, disabling ADMIN
     * MFA) depend on one development check. Keeping it in a single class means a rule change cannot be missed.
     */
    @ArchTest
    static final ArchRule profileChecksLiveOnlyInEnvironmentPolicy = noClasses()
            .that().areNotAssignableTo(EnvironmentPolicy.class)
            .should().callMethod(Environment.class, "getActiveProfiles")
            .orShould().callMethod(Environment.class, "acceptsProfiles", org.springframework.core.env.Profiles[].class)
            .because("EnvironmentPolicy is the only place allowed to decide whether this is a development setting");
}
