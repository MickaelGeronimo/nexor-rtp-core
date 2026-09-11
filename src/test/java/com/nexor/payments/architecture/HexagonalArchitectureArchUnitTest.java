package com.nexor.payments.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@DisplayName("Hexagonal Architecture Boundary Tests (ArchUnit)")
class HexagonalArchitectureArchUnitTest {

    // Import only production code, excluding test harnesses from architecture boundary scans
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nexor.payments");

    @Test
    @DisplayName("Domain layer must not depend on application or infrastructure layers")
    void domainShouldBeIndependent() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..");

        rule.check(classes);
    }

    @Test
    @DisplayName("Domain layer must never depend on Spring Framework")
    void domainShouldNotDependOnSpring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework..");

        rule.check(classes);
    }

    @Test
    @DisplayName("Inbound adapters (Web/REST) must never depend directly on Outbound adapters (Persistence/Clearing)")
    void inboundAdaptersMustNotDependOnOutboundAdapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure.adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("..infrastructure.adapter.out..");

        rule.check(classes);
    }

    @Test
    @DisplayName("Application layer (Use Cases & Sagas) must never depend on Infrastructure layer")
    void applicationLayerMustNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..infrastructure..");

        rule.check(classes);
    }
}
