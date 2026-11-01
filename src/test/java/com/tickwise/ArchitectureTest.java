package com.tickwise;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/** Enforces the module rules from ADR 0001 on every build. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.tickwise");

    @Test
    void modulesHaveNoDependencyCycles() {
        slices().matching("com.tickwise.(*)..").should().beFreeOfCycles().check(CLASSES);
    }

    @Test
    void repositoriesAreOnlyUsedInsideTheirOwnModule() {
        for (String module : new String[] {"tenant", "auth", "sla", "ticket", "assignment", "email", "notification", "reporting"}) {
            noClasses().that().resideOutsideOfPackage("com.tickwise." + module + "..")
                    .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.and(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo(Repository.class),
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("com.tickwise." + module + "..")))
                    .because("modules talk through services and events, never another module's repositories")
                    .check(CLASSES);
        }
    }

    @Test
    void entitiesDoNotLeakOutOfTheirModule() {
        for (String module : new String[] {"tenant", "sla", "ticket", "assignment", "email", "notification"}) {
            noClasses().that().resideOutsideOfPackage("com.tickwise." + module + "..")
                    .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.and(
                            com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith(Entity.class),
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("com.tickwise." + module + "..")))
                    .check(CLASSES);
        }
    }

    @Test
    void slaCalculationCoreIsPureJava() {
        classes().that().haveSimpleName("BusinessTimeCalculator")
                .or().haveSimpleName("BusinessCalendar")
                .or().haveSimpleName("WorkingWindow")
                .or().haveSimpleName("SlaClock")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.tickwise.sla")
                .because("the SLA arithmetic must stay side-effect free and framework independent")
                .check(CLASSES);
    }

    @Test
    void sharedKernelDependsOnNoModule() {
        noClasses().that().resideInAPackage("com.tickwise.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("com.tickwise.tenant..", "com.tickwise.auth..",
                        "com.tickwise.sla..", "com.tickwise.ticket..", "com.tickwise.assignment..", "com.tickwise.email..",
                        "com.tickwise.notification..", "com.tickwise.reporting..", "com.tickwise.seed..")
                .check(CLASSES);
    }
}
