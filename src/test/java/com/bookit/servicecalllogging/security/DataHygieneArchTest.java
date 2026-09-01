package com.bookit.servicecalllogging.security;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static enforcement of Constitution Principle VII (Data Hygiene).
 *
 * <p>Two complementary guarantees:
 * <ol>
 *   <li><strong>Structural</strong> (ArchUnit) — the logging package cannot even see a header
 *       bag, so it is structurally incapable of logging one.</li>
 *   <li><strong>Literal</strong> (constant-pool scan) — no compiled main class carries an
 *       {@code "Authorization"} or {@code "Cookie"} string constant, so no log statement can
 *       name a credential header even accidentally.</li>
 * </ol>
 *
 * <p>The literal scan reads {@code target/classes} rather than source, so it cannot be fooled
 * by string concatenation in source form: the compiler folds constants into the pool.
 */
class DataHygieneArchTest {

    private static final String BASE_PACKAGE = "com.bookit.servicecalllogging";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    @Test
    void theLoggingPackageCannotSeeAHeaderBagAtAll() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + ".logging")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.http.HttpHeaders")
                .because("Constitution Principle VII — the logger must be structurally unable to "
                        + "reach request or response headers");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void theLoggingPackageDependsOnNothingButItsOwnRecordAndTheLoggingFacade() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + ".logging")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.fasterxml..", "org.springframework.http..",
                        "org.springframework.web..", "io.micrometer..")
                .because("the log surface is fixed: only OutboundCallRecord fields may be emitted");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void noProductionClassCarriesACredentialHeaderNameAsAStringConstant() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path classesRoot = Path.of("target", "classes");

        assertThat(classesRoot).as("compiled classes must exist before this rule can be checked")
                .exists();

        try (Stream<Path> classFiles = Files.walk(classesRoot)) {
            for (Path classFile : classFiles.filter(p -> p.toString().endsWith(".class")).toList()) {
                String constantPool = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("Authorization", "Cookie", "Bearer", "authorization", "cookie")) {
                    if (constantPool.contains(forbidden)) {
                        offenders.add(classesRoot.relativize(classFile) + " contains \"" + forbidden + "\"");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("Constitution Principle VII — no compiled class may reference a credential "
                        + "header name; found: %s", offenders)
                .isEmpty();
    }

    @Test
    void thereAreNoPackageCyclesInTheStarter() {
        ArchRule rule = slices()
                .matching(BASE_PACKAGE + ".(*)..")
                .should().beFreeOfCycles();

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void theLoggedFieldSetIsExactlyTheOneTheConstitutionPermits() {
        // Constitution Principle VII (v1.1.0): source/destination correlation values, the parsed
        // responseCode, and — since the v1.1.0 amendment — the extracted business message.
        // Anything else appearing here is a data-hygiene regression, not a feature.
        List<String> permitted = List.of(
                "source", "destination", "httpMethod", "httpStatusCode", "httpStatusGroup",
                "responseCode", "message", "timestamp");

        List<String> actual = Stream.of(
                        com.bookit.servicecalllogging.logging.OutboundCallRecord.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actual)
                .as("Constitution Principle VII — the loggable field set is fixed and exhaustive")
                .containsExactlyInAnyOrderElementsOf(permitted);
    }
}
