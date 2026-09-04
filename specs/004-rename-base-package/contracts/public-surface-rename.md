# Contract: Public Surface Rename Mapping

This starter's "contract" with consuming teams is its public Java surface (extension-point
interfaces, the auto-configured properties class, and the auto-configuration entry point) plus its
Maven coordinate — not a network API. This document is the authoritative old→new FQCN/coordinate
mapping that the rename MUST realize exactly, with every simple name preserved (per spec FR-001,
FR-006).

## Maven Coordinate

| Attribute | Old | New |
|-----------|-----|-----|
| `groupId` | `com.bookit` | `com.telecelghana.play.app.common` |
| `artifactId` | `service-call-logging-spring-boot-starter` | *(unchanged)* |

## Public Extension Points (consumer-implementable / consumer-overridable)

| Simple Name | Old FQCN | New FQCN |
|-------------|----------|----------|
| `ResponseCodeExtractor` | `com.bookit.servicecalllogging.ResponseCodeExtractor` | `com.telecelghana.play.app.common.servicecalllogging.ResponseCodeExtractor` |
| `EnvelopeFieldExtractor` | `com.bookit.servicecalllogging.EnvelopeFieldExtractor` | `com.telecelghana.play.app.common.servicecalllogging.EnvelopeFieldExtractor` |
| `EnvelopeMatch` | `com.bookit.servicecalllogging.EnvelopeMatch` | `com.telecelghana.play.app.common.servicecalllogging.EnvelopeMatch` |
| `DestinationNameResolver` | `com.bookit.servicecalllogging.resolver.DestinationNameResolver` | `com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver` |

## Replaceable Default Beans (consumer-overridable via `@ConditionalOnMissingBean`)

Every bean the starter contributes is declared `@ConditionalOnMissingBean` (Principle III), so a
consumer can replace any of these by declaring their own bean of the same type. They are therefore
part of the migration surface just as much as the extension points above — a consuming team that
overrides one of these must update its import. README's "Overriding anything else" table names
several of them explicitly.

| Simple Name | Old FQCN | New FQCN |
|-------------|----------|----------|
| `CallLogger` | `com.bookit.servicecalllogging.logging.CallLogger` | `com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger` |
| `OutboundCallMetrics` | `com.bookit.servicecalllogging.metrics.OutboundCallMetrics` | `com.telecelghana.play.app.common.servicecalllogging.metrics.OutboundCallMetrics` |
| `DestinationUriResolver` | `com.bookit.servicecalllogging.uri.DestinationUriResolver` | `com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver` |
| `InboundUriResolver` | `com.bookit.servicecalllogging.uri.InboundUriResolver` | `com.telecelghana.play.app.common.servicecalllogging.uri.InboundUriResolver` |
| `OperationResolver` | `com.bookit.servicecalllogging.operation.OperationResolver` | `com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver` |
| `OutboundCallInterceptor` | `com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor` | `com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor` |
| `OutboundCallExchangeFilter` | `com.bookit.servicecalllogging.filter.OutboundCallExchangeFilter` | `com.telecelghana.play.app.common.servicecalllogging.filter.OutboundCallExchangeFilter` |

Overriding `CallLogger` or `OutboundCallMetrics` also means touching the types in their method
signatures, which moved with them: `OutboundCallRecord`
(`...servicecalllogging.logging.OutboundCallRecord`) and `Outcome`
(`...servicecalllogging.metrics.Outcome`).

The two customizer beans README lists as overridable **by name**
(`serviceCallLoggingRestTemplateCustomizer`, `serviceCallLoggingWebClientCustomizer`) take their
names from their `@Bean` method names, not from the package, so those names are **unchanged**.

## Auto-Configured Properties Class

| Simple Name | Old FQCN | New FQCN |
|-------------|----------|----------|
| `ServiceCallLoggingProperties` | `com.bookit.servicecalllogging.ServiceCallLoggingProperties` | `com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties` |

Configuration property **keys** (e.g. `service-call-logging.enabled`) are unaffected — they are
strings, not Java identifiers, and are out of scope for this rename (FR-006).

## Auto-Configuration Entry Points

| Simple Name | Old FQCN | New FQCN |
|-------------|----------|----------|
| `ServiceCallLoggingAutoConfiguration` | `com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration` | `com.telecelghana.play.app.common.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration` |
| `RestTemplateInstrumentationConfiguration` | `com.bookit.servicecalllogging.autoconfigure.RestTemplateInstrumentationConfiguration` | `com.telecelghana.play.app.common.servicecalllogging.autoconfigure.RestTemplateInstrumentationConfiguration` |
| `WebClientInstrumentationConfiguration` | `com.bookit.servicecalllogging.autoconfigure.WebClientInstrumentationConfiguration` | `com.telecelghana.play.app.common.servicecalllogging.autoconfigure.WebClientInstrumentationConfiguration` |

**Registration file**: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
MUST list the new FQCN of `ServiceCallLoggingAutoConfiguration` — this is how Spring Boot discovers
the starter, and a stale entry here would silently disable the entire starter for every consumer
(a Principle I / III violation), so it is the single highest-risk artifact in this rename.

## Build-Gate References (not consumer-facing, but package-name-derived)

These are not FQCNs a consumer imports, but they are package-name strings that gate build quality.
A stale one does not fail — it silently matches nothing and passes, which is worse.

| Location | Old value | New value |
|----------|-----------|-----------|
| `pom.xml` — JaCoCo `auto-configuration-matrix-gate` rule `<include>` | `com.bookit.servicecalllogging.autoconfigure` | `com.telecelghana.play.app.common.servicecalllogging.autoconfigure` |
| `DataHygieneArchTest.BASE_PACKAGE` (ArchUnit scan root) | `"com.bookit.servicecalllogging"` | `"com.telecelghana.play.app.common.servicecalllogging"` |
| `DisabledStarterTest` bean-filter prefix | `"com.bookit.servicecalllogging"` | `"com.telecelghana.play.app.common.servicecalllogging"` |

## Names Derived From the Package (change as a side effect)

Renaming the package changes every identifier computed from it, even though no logic changed.
These are consumer-observable and are documented for consumers in README's migration table.

| Derived name | Old | New |
|--------------|-----|-----|
| SLF4J logger category (per-call telemetry) | `com.bookit.servicecalllogging.logging.CallLogger` | `com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger` |
| SLF4J logger category (startup warning) | `com.bookit.servicecalllogging.resolver.DestinationNameResolver` | `com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver` |
| `@ConfigurationProperties` bean name (also the `/actuator/configprops` key) | `service-call-logging-com.bookit.servicecalllogging.ServiceCallLoggingProperties` | `service-call-logging-com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties` |

**Unaffected** (string literals, not derived from the package): all `service-call-logging.*`
configuration property keys, Micrometer meter names and tag keys/values, HTTP header names, log
message text and log field names, and the `@Bean`-method-derived bean names.

## Compatibility Statement

No shim, alias, or deprecated forwarding type is retained under the old `com.bookit.*` names or
`groupId` (see plan.md's Complexity Tracking for the justification). A consumer currently on the
old coordinate must update both its dependency declaration and its imports in one step to adopt
the renamed starter; there is no dual-package transition period.
