package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.EnvelopeFieldExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties;
import com.telecelghana.play.app.common.servicecalllogging.extractor.JacksonEnvelopeFieldExtractor;
import com.telecelghana.play.app.common.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.metrics.OutboundCallMetrics;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Root auto-configuration for the starter — the single entry point registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>The {@code @ConditionalOnProperty} guard on this class is the master switch: setting
 * {@code service-call-logging.enabled=false} suppresses this class and therefore every bean
 * beneath it, including both imported instrumentation configurations (FR-023).
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}, so a consumer-defined bean of the
 * same type silently wins (FR-021, Constitution Principle III).
 */
@AutoConfiguration(afterName = {
        // @ConditionalOnBean(MeterRegistry.class) below only sees bean definitions registered
        // before this class is evaluated, so we must run after Actuator has contributed the
        // registry — otherwise metrics are silently never recorded in a real application.
        // Referenced by name because spring-boot-actuator-autoconfigure is not a dependency of
        // this starter (Constitution Principle II).
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnProperty(prefix = "service-call-logging", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ServiceCallLoggingProperties.class)
@Import({RestTemplateInstrumentationConfiguration.class, WebClientInstrumentationConfiguration.class})
public class ServiceCallLoggingAutoConfiguration {

    /**
     * Reads {@code spring.application.name} straight from the {@link Environment} rather than
     * requiring it as a property of this starter, so consumers configure it in exactly one
     * conventional place.
     */
    @Bean
    @ConditionalOnMissingBean
    public DestinationNameResolver destinationNameResolver(Environment environment) {
        return new DestinationNameResolver(environment.getProperty("spring.application.name"));
    }

    @Bean
    @ConditionalOnMissingBean
    public CallLogger callLogger() {
        return new CallLogger();
    }

    /**
     * The default extractor, active only when Jackson is present and the consumer has not
     * supplied their own {@link ResponseCodeExtractor} (FR-022).
     *
     * <p>{@code @ConditionalOnClass} is safe to place on a {@code @Bean} method with a
     * {@code name} reference: Spring evaluates the condition from ASM-read metadata and never
     * invokes the method — so {@code JacksonResponseCodeExtractor}, and through it
     * {@code ObjectMapper}, is never loaded when Jackson is absent.
     */
    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    @ConditionalOnMissingBean(ResponseCodeExtractor.class)
    public ResponseCodeExtractor jacksonResponseCodeExtractor(EnvelopeFieldExtractor envelopeFieldExtractor) {
        return new JacksonResponseCodeExtractor(envelopeFieldExtractor);
    }

    /**
     * The envelope matching engine (FR-004). Registered independently of
     * {@link ResponseCodeExtractor}: a consumer who has replaced code extraction with their own
     * bean still gets a message and still has their code classified against the matched
     * combination's successful value (FR-016).
     *
     * <p>Guarded on Jackson exactly like the default extractor; without it, no message is read
     * and the starter behaves as it did before this feature.
     */
    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    @ConditionalOnMissingBean(EnvelopeFieldExtractor.class)
    public EnvelopeFieldExtractor envelopeFieldExtractor(ServiceCallLoggingProperties properties) {
        return new JacksonEnvelopeFieldExtractor(properties.envelopes());
    }

    /**
     * Resolves the destination URI's two surfaces (spec 003, FR-003 to FR-005). Replaceable like
     * every other starter bean — a consumer wanting different normalisation declares their own.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver destinationUriResolver() {
        return new com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver();
    }

    /**
     * Resolves the inbound request's path (spec 003, FR-006 to FR-008). Contributes no dependency
     * of its own: the servlet attribute key is read as a literal string precisely so that a
     * consumer with no servlet stack is unaffected (Principle II).
     */
    @Bean
    @ConditionalOnMissingBean
    public com.telecelghana.play.app.common.servicecalllogging.uri.InboundUriResolver inboundUriResolver() {
        return new com.telecelghana.play.app.common.servicecalllogging.uri.InboundUriResolver();
    }

    /**
     * Resolves the caller-supplied business operation (spec 003, FR-017 to FR-025).
     *
     * <p>Deliberately a singleton: the distinct-value cap is per resolver instance, so one shared
     * bean means one cap for the whole application rather than a fresh, separately-exhaustible cap
     * per client.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver operationResolver() {
        return new com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver();
    }

    /**
     * The metrics recorder, created only when the consumer already has a {@link MeterRegistry}
     * — that is, when Actuator is on their classpath. Without one, no metrics are recorded and
     * nothing fails: instrumentation carries on logging (Constitution Principle II).
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public OutboundCallMetrics outboundCallMetrics(MeterRegistry meterRegistry,
                                                   ServiceCallLoggingProperties properties) {
        return new OutboundCallMetrics(meterRegistry, properties);
    }
}
