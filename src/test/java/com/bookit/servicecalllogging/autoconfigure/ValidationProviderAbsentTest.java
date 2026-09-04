package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Constitution Principle II — Zero Forced Footprint. A consumer whose classpath carries the
 * Bean Validation API without a provider (a very common shape: the API arrives transitively,
 * {@code spring-boot-starter-validation} does not) must still start.
 *
 * <p>Spring's configuration-properties binder decides whether to run JSR-303 validation from
 * the presence of {@code jakarta.validation.Validator} alone — it never checks for an
 * implementation. So a single {@code @Validated} on our properties class was enough to make
 * such an application fail at startup with "The Bean Validation API is on the classpath but no
 * implementation could be found". This starter therefore validates its own properties in the
 * records' constructors and asks Spring for no validator at all.
 */
@Tag("non-intrusion")
class ValidationProviderAbsentTest {

    @Test
    void theTestClasspathCarriesTheValidationApiWithoutAnImplementation() {
        assertThat(ClassUtils.isPresent("jakarta.validation.Validator", null))
                .as("the API must be present, or this whole test proves nothing")
                .isTrue();

        assertThatExceptionOfType(ValidationException.class)
                .as("no provider may be present, or this whole test proves nothing")
                .isThrownBy(Validation::buildDefaultValidatorFactory);
    }

    @Test
    void theStarterStartsWithNoValidationProviderOnTheClasspath() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ServiceCallLoggingProperties.class));
    }

    @Test
    void theFourNewMetricsKeysStillBindWithNoValidationProviderOnTheClasspath() {
        // spec 003 (T007) — Principle II: the keys added by this feature must bind, with their
        // documented defaults, on a classpath that carries the API but no implementation.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class))
                .withPropertyValues(
                        "service-call-logging.metrics.destination-uri-tag-name=dest_path",
                        "service-call-logging.metrics.latency-buckets=50ms,1s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServiceCallLoggingProperties.Metrics metrics =
                            context.getBean(ServiceCallLoggingProperties.class).metrics();

                    assertThat(metrics.destinationUriTagName()).isEqualTo("dest_path");
                    assertThat(metrics.inboundUriTagName()).isEqualTo("inbound_uri");
                    assertThat(metrics.operationTagName()).isEqualTo("operation");
                    assertThat(metrics.latencyBuckets()).hasSize(2);
                });
    }

    @Test
    void theNewTagNameConstraintsAreEnforcedWithNoValidationProviderOnTheClasspath() {
        // The @NotBlank annotations cannot fire without a provider, so enforcement lives in the
        // record's constructor — proven here on the provider-free classpath (spec 003, T007).
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class));

        runner.withPropertyValues("service-call-logging.metrics.destination-uri-tag-name=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("service-call-logging.metrics.inbound-uri-tag-name= ")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("service-call-logging.metrics.operation-tag-name=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void propertyConstraintsAreStillEnforcedWithNoValidationProviderOnTheClasspath() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class));

        runner.withPropertyValues("service-call-logging.source-header-name=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("service-call-logging.metrics.prefix= ")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("service-call-logging.max-body-bytes=-1")
                .run(context -> assertThat(context).hasFailed());
    }
}