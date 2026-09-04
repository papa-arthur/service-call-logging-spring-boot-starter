package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.filter.OutboundCallExchangeFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution Principle II — the reactive path must activate only when a reactive HTTP client
 * is actually on the consumer's classpath (FR-025).
 */
class WebClientConfigurationActivationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    @Test
    void webClientOnTheClasspathActivatesTheReactivePath() {
        this.runner.run(context -> {
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
            assertThat(context).hasBean("serviceCallLoggingWebClientCustomizer");
        });
    }

    @Test
    void theCustomizerActuallyRegistersTheFilterOnABuilder() {
        this.runner.run(context -> {
            WebClient.Builder builder = WebClient.builder();
            context.getBean(WebClientCustomizer.class).customize(builder);

            builder.filters(filters -> assertThat(filters)
                    .hasAtLeastOneElementOfType(OutboundCallExchangeFilter.class));
        });
    }

    @Test
    void webClientAbsentFromTheClasspathDeactivatesTheReactivePathEntirely() {
        this.runner.withClassLoader(new FilteredClassLoader(WebClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboundCallExchangeFilter.class);
                    assertThat(context).doesNotHaveBean("serviceCallLoggingWebClientCustomizer");
                });
    }

    @Test
    void aConsumerSuppliedFilterReplacesTheStarterBean() {
        this.runner.withUserConfiguration(CustomFilterConfig.class).run(context -> {
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
            assertThat(context.getBean(OutboundCallExchangeFilter.class))
                    .isSameAs(context.getBean("myOwnFilter"));
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomFilterConfig {
        @org.springframework.context.annotation.Bean
        OutboundCallExchangeFilter myOwnFilter(
                com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver resolver,
                com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger callLogger,
                com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties properties) {
            return new OutboundCallExchangeFilter(resolver, bytes -> java.util.Optional.empty(),
                    callLogger, properties);
        }
    }
}
