package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.EnvelopeFieldExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties;
import com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.metrics.OutboundCallMetrics;
import com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.uri.CapturingUriTemplateHandler;
import com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver;
import com.telecelghana.play.app.common.servicecalllogging.uri.InboundUriResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Blocking-path instrumentation, active only when {@code RestTemplate} is on the consumer's
 * classpath (FR-024, Constitution Principle II).
 *
 * <p>The class-level {@code @ConditionalOnClass} deliberately uses the string {@code name}
 * form. A {@code .class} literal here would force the JVM to resolve {@code RestTemplate} when
 * reading this configuration class's annotations, throwing {@code NoClassDefFoundError} in a
 * consumer that has no blocking client — exactly the startup failure Principle II forbids.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
class RestTemplateInstrumentationConfiguration {

    /**
     * The extractor is optional: with no Jackson and no consumer-supplied bean there is nothing
     * to parse with, so the starter degrades to {@code responseCode=absent} rather than
     * failing to start.
     */
    @Bean
    @ConditionalOnMissingBean
    OutboundCallInterceptor outboundCallInterceptor(DestinationNameResolver destinationNameResolver,
                                                    ObjectProvider<ResponseCodeExtractor> responseCodeExtractor,
                                                    CallLogger callLogger,
                                                    ObjectProvider<OutboundCallMetrics> outboundCallMetrics,
                                                    ServiceCallLoggingProperties properties,
                                                    ObjectProvider<EnvelopeFieldExtractor> envelopeFieldExtractor,
                                                    DestinationUriResolver destinationUriResolver,
                                                    InboundUriResolver inboundUriResolver,
                                                    OperationResolver operationResolver) {
        return new OutboundCallInterceptor(
                destinationNameResolver,
                responseCodeExtractor.getIfAvailable(() -> bytes -> Optional.empty()),
                callLogger,
                // Absent whenever the consumer has no MeterRegistry; logging continues regardless.
                outboundCallMetrics.getIfAvailable(),
                properties,
                // Absent whenever Jackson is missing; the message is then simply never read.
                envelopeFieldExtractor.getIfAvailable(),
                // The auto-configured singletons from ServiceCallLoggingAutoConfiguration (spec
                // 003 remediation, T062). Passing these explicitly — rather than falling through
                // to the constructor overload that creates its own defaults — is what makes the
                // operation admission cap actually shared with the WebClient path and what lets a
                // consumer-supplied override bean of any of the three take effect.
                destinationUriResolver,
                inboundUriResolver,
                operationResolver);
    }

    /**
     * Applies the interceptor to every {@code RestTemplate} built through the
     * {@code RestTemplateBuilder}. Named so a consumer can replace just this wiring.
     *
     * <p>Known limitation: a {@code RestTemplate} created with {@code new RestTemplate()} never
     * passes through a customizer and is therefore not instrumented.
     */
    @Bean
    @ConditionalOnMissingBean(name = "serviceCallLoggingRestTemplateCustomizer")
    RestTemplateCustomizer serviceCallLoggingRestTemplateCustomizer(OutboundCallInterceptor interceptor) {
        return restTemplate -> {
            restTemplate.getInterceptors().add(interceptor);
            // Spec 003 (T021): wrap the template's own handler so the URI template is captured
            // where it is still known. Without this the interceptor only ever sees an expanded
            // URI and every blocking-path call would report the `unresolved` placeholder in its
            // destination-URI metric tag (research.md §1).
            restTemplate.setUriTemplateHandler(
                    new CapturingUriTemplateHandler(restTemplate.getUriTemplateHandler()));
        };
    }
}
