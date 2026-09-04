package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution Principle II — the blocking path must activate only when a blocking HTTP client
 * is actually on the consumer's classpath (FR-024).
 */
class RestTemplateConfigurationActivationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    @Test
    void restTemplateOnTheClasspathActivatesTheBlockingPath() {
        this.runner.run(context -> {
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context).hasBean("serviceCallLoggingRestTemplateCustomizer");
        });
    }

    @Test
    void theCustomizerActuallyRegistersTheInterceptorOnABuiltRestTemplate() {
        this.runner.run(context -> {
            RestTemplate restTemplate = new RestTemplate();
            context.getBean(RestTemplateCustomizer.class).customize(restTemplate);

            assertThat(restTemplate.getInterceptors())
                    .hasAtLeastOneElementOfType(OutboundCallInterceptor.class);
        });
    }

    @Test
    void restTemplateAbsentFromTheClasspathDeactivatesTheBlockingPathEntirely() {
        this.runner.withClassLoader(new FilteredClassLoader(RestTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboundCallInterceptor.class);
                    assertThat(context).doesNotHaveBean("serviceCallLoggingRestTemplateCustomizer");
                });
    }

    @Test
    void aConsumerSuppliedInterceptorReplacesTheStarterBean() {
        this.runner.withUserConfiguration(CustomInterceptorConfig.class).run(context -> {
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context.getBean(OutboundCallInterceptor.class))
                    .isSameAs(context.getBean("myOwnInterceptor"));
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomInterceptorConfig {
        @org.springframework.context.annotation.Bean
        OutboundCallInterceptor myOwnInterceptor(
                com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver resolver,
                com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger callLogger,
                com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties properties) {
            return new OutboundCallInterceptor(resolver, bytes -> java.util.Optional.empty(),
                    callLogger, properties);
        }
    }
}
