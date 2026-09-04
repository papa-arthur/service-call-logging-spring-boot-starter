package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.EnvelopeFieldExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties;
import com.telecelghana.play.app.common.servicecalllogging.filter.OutboundCallExchangeFilter;
import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.metrics.OutboundCallMetrics;
import com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver;
import com.telecelghana.play.app.common.servicecalllogging.uri.InboundUriResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Reactive-path instrumentation, active only when {@code WebClient} is on the consumer's
 * classpath (FR-025, Constitution Principle II).
 *
 * <p>As with the blocking configuration, the class-level {@code @ConditionalOnClass} uses the
 * string {@code name} form so that reading this class's annotations never resolves
 * {@code WebClient} in a consumer that does not have WebFlux.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
class WebClientInstrumentationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboundCallExchangeFilter outboundCallExchangeFilter(DestinationNameResolver destinationNameResolver,
                                                          ObjectProvider<ResponseCodeExtractor> responseCodeExtractor,
                                                          CallLogger callLogger,
                                                          ObjectProvider<OutboundCallMetrics> outboundCallMetrics,
                                                          ServiceCallLoggingProperties properties,
                                                          ObjectProvider<EnvelopeFieldExtractor> envelopeFieldExtractor,
                                                          DestinationUriResolver destinationUriResolver,
                                                          InboundUriResolver inboundUriResolver,
                                                          OperationResolver operationResolver) {
        return new OutboundCallExchangeFilter(
                destinationNameResolver,
                responseCodeExtractor.getIfAvailable(() -> bytes -> Optional.empty()),
                callLogger,
                // Absent whenever the consumer has no MeterRegistry; logging continues regardless.
                outboundCallMetrics.getIfAvailable(),
                properties,
                // Absent whenever Jackson is missing; the message is then simply never read.
                envelopeFieldExtractor.getIfAvailable(),
                // The auto-configured singletons from ServiceCallLoggingAutoConfiguration (spec
                // 003 remediation, T062): shared with the RestTemplate path so the operation
                // admission cap, and any consumer override of the three resolver beans, apply
                // application-wide rather than per client type.
                destinationUriResolver,
                inboundUriResolver,
                operationResolver);
    }

    /**
     * Applies the filter to every {@code WebClient} built through an injected
     * {@code WebClient.Builder}. Named so a consumer can replace just this wiring.
     *
     * <p>Known limitation: a client created with {@code WebClient.create()} never passes
     * through a customizer and is therefore not instrumented.
     */
    @Bean
    @ConditionalOnMissingBean(name = "serviceCallLoggingWebClientCustomizer")
    WebClientCustomizer serviceCallLoggingWebClientCustomizer(OutboundCallExchangeFilter filter) {
        return builder -> builder.filter(filter);
    }
}
