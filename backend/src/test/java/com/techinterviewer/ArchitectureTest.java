package com.techinterviewer;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.techinterviewer", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] FEATURE_PACKAGES = {
        "com.techinterviewer.user..",
        "com.techinterviewer.catalog..",
        "com.techinterviewer.interview..",
        "com.techinterviewer.evaluation..",
        "com.techinterviewer.ai..",
        "com.techinterviewer.rag..",
        "com.techinterviewer.admin..",
        "com.techinterviewer.system..",
        "com.techinterviewer.resume..",
        "com.techinterviewer.questionbank.."
    };

    /** What the rest of the application may use of the security package: who is calling, nothing of how it is decided. */
    private static final DescribedPredicate<JavaClass> SECURITY_PUBLIC_CONTRACT =
            simpleName("AuthenticatedUser").or(simpleName("CurrentUser"));

    /** An entity of the persistence model: anything Hibernate maps. */
    private static final DescribedPredicate<JavaClass> PERSISTENT_CLASSES =
            DescribedPredicate.describe("JPA entities, mapped superclasses and embeddables",
                    (JavaClass type) -> type.isAnnotatedWith(Entity.class)
                            || type.isAnnotatedWith(MappedSuperclass.class)
                            || type.isAnnotatedWith(Embeddable.class));

    private static final DescribedPredicate<JavaClass> REPOSITORIES =
            DescribedPredicate.describe("repositories",
                    (JavaClass type) -> type.isAssignableTo(Repository.class) || type.getPackageName().contains(".repository"));

    @ArchTest
    static final ArchRule commonDoesNotDependOnFeaturePackages = noClasses()
            .that().resideInAPackage("com.techinterviewer.common..")
            .should().dependOnClassesThat().resideInAnyPackage(FEATURE_PACKAGES)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule securityInternalsAreUsedOnlyBySecurityItself = noClasses()
            .that().resideOutsideOfPackages("com.techinterviewer.security..", "com.techinterviewer.config..")
            .should().dependOnClassesThat(resideInAPackage("com.techinterviewer.security..").and(not(SECURITY_PUBLIC_CONTRACT)))
            .as("classes outside security and config may use only AuthenticatedUser and @CurrentUser of the security package")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule aiProviderDoesNotDependOnDomainPackages = noClasses()
            .that().resideInAPackage("com.techinterviewer.ai.provider..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.techinterviewer.user..",
                    "com.techinterviewer.catalog..",
                    "com.techinterviewer.interview..",
                    "com.techinterviewer.evaluation..",
                    "com.techinterviewer.rag..",
                    "com.techinterviewer.admin..",
                    "com.techinterviewer.ai.agents..",
                    "com.techinterviewer.ai.structured..")
            .allowEmptyShould(true);

    // ---- the pure core: no framework, no persistence -------------------------------------------------------------

    @ArchTest
    static final ArchRule theInterviewEngineAndTheEvaluationRulesArePureJava = noClasses()
            .that().resideInAPackage("com.techinterviewer.interview.engine..")
            .or().resideInAPackage("com.techinterviewer.evaluation") // the top level only: evaluation.domain holds the entity
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .orShould().dependOnClassesThat(REPOSITORIES)
            .as("the interview engine and the pure evaluation classes must not depend on Spring, JPA or any repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule thePortsSpeakInValueTypesOnly = noClasses()
            .that().resideInAPackage("com.techinterviewer.interview.port..")
            .should().dependOnClassesThat(PERSISTENT_CLASSES)
            .orShould().dependOnClassesThat(REPOSITORIES)
            .as("the AI ports must not depend on entities or repositories: requests and results are value types")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entitiesDoNotKnowControllersOrServices = noClasses()
            .that(PERSISTENT_CLASSES)
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..service..", "..dto..")
            .orShould().dependOnClassesThat().areAnnotatedWith(RestController.class)
            .orShould().dependOnClassesThat().areAnnotatedWith(Service.class)
            .as("entities must not depend on controllers, services or DTOs")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersNeverTouchEntities = noClasses()
            .that().resideInAPackage("..api..")
            .or().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat(PERSISTENT_CLASSES)
            .as("controllers and API classes must not accept, return or use JPA entities: map them to DTOs in the service layer")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule dtosNeverTouchEntities = noClasses()
            .that().resideInAPackage("..dto..")
            .should().dependOnClassesThat(PERSISTENT_CLASSES)
            .as("DTOs are plain records: mapping from an entity belongs to a mapper or service outside the dto package")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule theAiLayerIsNotAHardDependencyOfTheInterviewCore = noClasses()
            .that().resideInAnyPackage("com.techinterviewer.interview.engine..", "com.techinterviewer.interview.service..")
            .should().dependOnClassesThat().resideInAPackage("com.techinterviewer.ai..")
            .as("the interview engine and services reach the AI only through the interview.port interfaces")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule persistentClassesAreMappedEntitiesOnlyInTheirOwnFeaturePackages = classes()
            .that(PERSISTENT_CLASSES)
            .should().resideOutsideOfPackages("..api..", "..dto..", "..port..", "..engine..")
            .as("entities live in domain, persistence or common packages, never in api, dto, port or engine packages")
            .allowEmptyShould(true);
}
