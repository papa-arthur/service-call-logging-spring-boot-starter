# Implementation Plan: Outbound HTTP Observability Starter

**Branch**: `001-outbound-http-observability` | **Date**: 2026-08-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-outbound-http-observability/spec.md`

## Summary

A Spring Boot 3 auto-configurable Maven starter that instruments every outbound HTTP call
made through a consuming service's `RestTemplate` or `WebClient`. It stamps requests with
`X-Source-Service` and `X-Destination-Service` correlation headers, reads `responseCode`
from the JSON response envelope (0 = success, 1 = failure), and logs the result alongside the
headers. It also emits Micrometer counters by destination, outcome, and HTTP status group,
exportable via Prometheus for Grafana dashboards and alerts. Instrumentation is entirely
transparent — zero consumer code changes required; non-intrusion is the overriding guarantee.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Build Tool**: Maven 3.8+

**Framework**: Spring Boot 3.3.x

**Primary Dependencies**:
- `spring-boot-autoconfigure` (always)
- `micrometer-core` (always)
- `spring-web` (optional — RestTemplate path)
- `spring-webflux` (optional — WebClient path)
- `jackson-databind` (optional — default JSON extractor)

**Storage**: N/A (stateless instrumentation)

**Testing**: JUnit 5, AssertJ, `ApplicationContextRunner` (auto-config slice tests),
JDK `HttpServer` (integration stub — zero extra dependency)

**Target Platform**: Any JVM-based Spring Boot 3.x service

**Performance Goals**: Per-call overhead bounded; body buffering capped at 1 MB (configurable)

**Constraints**: Zero forced transitive dependencies on consumers; Non-Intrusion wins all conflicts

**Scale/Scope**: Single-artifact Maven starter; shared across multiple independent service teams

## Constitution Check

*Verified pre-design and post-design. All 10 principles pass.*

| Principle | Verification |
|---|---|
| I. Non-Intrusion | All instrumentation steps wrapped; exceptions degrade to absent telemetry; response body re-wrapped for re-readability; `NonIntrusionRestTemplateTest` + `NonIntrusionWebClientTest` as CI gate |
| II. Zero Forced Footprint | `spring-web`, `spring-webflux`, `jackson-databind` declared `<optional>true</optional>`; `@ConditionalOnClass(name=...)` string form prevents NCDFE |
| III. Auto-Configured, Fully Overridable | All beans use `@ConditionalOnMissingBean`; `ResponseCodeExtractor` SPI; `service-call-logging.enabled=false` disables all beans |
| IV. Backward Compatibility | Semver policy; japicmp binary-compat gate on MINOR/PATCH releases; config keys documented; deprecate-then-remove required |
| V. Test-First (TDD) | Non-Intrusion regression suite (11 tests, `@Tag("non-intrusion")`); auto-config matrix (100% coverage, `ApplicationContextRunner`) |
| VI. Graceful Degradation | 1 MB body cap; `SequenceInputStream` re-emission on oversized blocking response; reactive `takeUntilByteCount` side-channel; `network-error` as pessimistic default |
| VII. Data Hygiene | `CallLogger` emits only source, destination, responseCode; ArchUnit rule (`DataHygieneArchTest`) blocks log references to Authorization/Cookie |
| VIII. Documentation | `contracts/configuration.md` (all 9 keys + defaults); `contracts/metrics-schema.md`; `quickstart.md` (known limitations explicit) |
| IX. Spec-Driven Traceability | Every design decision maps to a FR; spec link in every PR required |
| X. Simplicity / YAGNI | Single Maven module; no speculative abstractions; `ObjectProvider` only for the Jackson-absent edge case |

## Project Structure

### Documentation (this feature)

```text
specs/001-outbound-http-observability/
├── plan.md              # This file
├── research.md          # Technology decisions
├── data-model.md        # Entity model
├── quickstart.md        # End-to-end validation guide
├── contracts/
│   ├── configuration.md             # All config keys + defaults + known limitations
│   ├── response-code-extractor-spi.md  # ResponseCodeExtractor interface contract
│   └── metrics-schema.md            # Prometheus metric schema + PromQL examples
└── tasks.md             # Created by /speckit-tasks (not yet)
```

### Source Code (repository root)

```text
pom.xml

src/
├── main/
│   ├── java/com/bookit/servicecalllogging/
│   │   ├── ServiceCallLoggingProperties.java       # @ConfigurationProperties — all 9 keys
│   │   ├── ResponseCodeExtractor.java              # PUBLIC SPI interface (consumer-replaceable)
│   │   ├── autoconfigure/
│   │   │   ├── ServiceCallLoggingAutoConfiguration.java          # Root @AutoConfiguration
│   │   │   ├── RestTemplateInstrumentationConfiguration.java     # @ConditionalOnClass(RestTemplate)
│   │   │   └── WebClientInstrumentationConfiguration.java        # @ConditionalOnClass(WebClient)
│   │   ├── interceptor/
│   │   │   ├── OutboundCallInterceptor.java         # ClientHttpRequestInterceptor (RestTemplate)
│   │   │   └── BufferingClientHttpResponse.java     # ClientHttpResponse decorator; re-readable
│   │   ├── filter/
│   │   │   └── OutboundCallExchangeFilter.java      # ExchangeFilterFunction (WebClient)
│   │   ├── resolver/
│   │   │   └── DestinationNameResolver.java         # service_name header → host:port fallback
│   │   ├── extractor/
│   │   │   └── JacksonResponseCodeExtractor.java    # Default ResponseCodeExtractor
│   │   ├── logging/
│   │   │   └── CallLogger.java                      # SLF4J structured log; no credential fields
│   │   └── metrics/
│   │       ├── OutboundCallMetrics.java             # Micrometer Counter wrapper
│   │       ├── Outcome.java                         # Enum: SUCCESS / FAILURE / ABSENT
│   │       └── ResponseCodeResult.java              # Record: outcome + nullable rawCode
│   └── resources/
│       └── META-INF/
│           └── spring/
│               └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── test/
    └── java/com/bookit/servicecalllogging/
        ├── autoconfigure/
        │   ├── ServiceCallLoggingAutoConfigurationTest.java    # Full matrix: enabled/disabled × classpath × override
        │   ├── RestTemplateConfigurationActivationTest.java    # RestTemplate conditional activation
        │   ├── WebClientConfigurationActivationTest.java       # WebClient conditional activation
        │   ├── ResponseCodeExtractorOverrideTest.java          # Consumer SPI override
        │   ├── DestinationResolverOverrideTest.java
        │   ├── MetricsAbsentWhenNoRegistryTest.java
        │   └── DisabledStarterTest.java
        ├── interceptor/
        │   ├── BufferingClientHttpResponseTest.java            # Unit: re-readability, cap, oversized
        │   ├── OutboundCallInterceptorTest.java                # Unit: header stamping, flow
        │   ├── NonIntrusionRestTemplateTest.java               # @Tag("non-intrusion") CI gate
        │   └── RestTemplateIntegrationTest.java               # Full-stack with JDK HttpServer stub
        ├── filter/
        │   ├── OutboundCallExchangeFilterTest.java             # Unit: reactive flow
        │   ├── NonIntrusionWebClientTest.java                  # @Tag("non-intrusion") CI gate
        │   └── WebClientIntegrationTest.java                  # Full-stack with JDK HttpServer stub
        ├── resolver/
        │   └── DestinationNameResolverTest.java                # Unit: all priority/edge cases
        ├── extractor/
        │   └── JacksonResponseCodeExtractorTest.java           # Unit: all parse cases
        ├── metrics/
        │   ├── OutboundCallMetricsTest.java                    # Unit: counter increment, tags
        │   └── MetricsIntegrationTest.java                     # Full-stack: correct labels verified
        ├── logging/
        │   └── CallLoggerTest.java                             # Unit: log content, no credentials
        └── security/
            └── DataHygieneArchTest.java                        # ArchUnit: no Authorization/Cookie in logs
```

**Structure Decision**: Single Maven module (Simplicity / YAGNI — Principle X). No separate
`-autoconfigure` sub-module because there is only one artifact and the consumer always wants
both.

## Maven pom.xml Design

### Coordinates

```xml
<groupId>com.bookit</groupId>
<artifactId>service-call-logging-spring-boot-starter</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>jar</packaging>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.13</version>
    <relativePath/>
</parent>
```

### Dependency Layout

| Artifact | Scope | `<optional>` | Why |
|---|---|---|---|
| `spring-boot-autoconfigure` | compile | — | Always: `@AutoConfiguration`, `@Conditional*` infrastructure |
| `micrometer-core` | compile | — | Always: `Counter` / `MeterRegistry` API |
| `spring-web` | compile | `true` | RestTemplate path; not forced on consumer |
| `spring-webflux` | compile | `true` | WebClient path; not forced on consumer |
| `jackson-databind` | compile | `true` | Default JSON extractor; virtually always present but not guaranteed |
| `spring-boot-configuration-processor` | annotationProcessorPath | — | IDE metadata; no runtime footprint |
| `spring-boot-starter-test` | test | — | JUnit 5, Mockito, AssertJ, `ApplicationContextRunner` |
| `spring-boot-starter-web` | test | — | Full RestTemplate stack for integration tests |
| `spring-boot-starter-webflux` | test | — | Full WebClient stack for reactive integration tests |
| `spring-boot-starter-actuator` | test | — | `MeterRegistry` for metric integration tests |
| `reactor-test` | test | — | `StepVerifier` for reactive assertions |

### Plugin Configuration

```xml
<!-- maven-compiler-plugin: annotation processor path for config metadata -->
<!-- maven-surefire-plugin: separate execution for @Tag("non-intrusion") CI gate -->
<!-- japicmp-maven-plugin: binary compatibility check on MINOR/PATCH releases -->
```

## Auto-Configuration Wiring

### `AutoConfiguration.imports` content

```
com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration
```

### Bean Conditional Matrix

| Bean | Configuration Class | `@ConditionalOnClass` | `@ConditionalOnProperty` | `@ConditionalOnMissingBean` | `@ConditionalOnBean` |
|---|---|---|---|---|---|
| `DestinationNameResolver` | Root | — | enabled=true (via class) | `DestinationNameResolver` | — |
| `JacksonResponseCodeExtractor` | Root | `ObjectMapper` | enabled=true (via class) | `ResponseCodeExtractor` | — |
| `CallLogger` | Root | — | enabled=true (via class) | `CallLogger` | — |
| `OutboundCallMetrics` | Root | — | enabled=true (via class) | `OutboundCallMetrics` | `MeterRegistry` |
| `OutboundCallInterceptor` | RestTemplate | `RestTemplate` (string) | — | `OutboundCallInterceptor` | — |
| `RestTemplateCustomizer` (named) | RestTemplate | `RestTemplate` (string) | — | name=`serviceCallLoggingRestTemplateCustomizer` | — |
| `OutboundCallExchangeFilter` | WebClient | `WebClient` (string) | — | `OutboundCallExchangeFilter` | — |
| `WebClientCustomizer` (named) | WebClient | `WebClient` (string) | — | name=`serviceCallLoggingWebClientCustomizer` | — |

The root `@AutoConfiguration` is annotated with `@ConditionalOnProperty(prefix="service-call-logging", name="enabled", havingValue="true", matchIfMissing=true)` — this gates ALL beans when the master switch is `false`.

## Instrumentation Design

### RestTemplate Path

```
intercept(request, body, execution):
  sourceName       ← DestinationNameResolver.sourceName (set at startup; "unknown" + WARN if absent)
  destinationName  ← DestinationNameResolver.resolve(request)
  wrappedRequest   ← HttpRequestWrapper(request) + set source/destination headers
  httpStatusGroup  = "network-error"                      ← pessimistic default
  rawResponse      = null

  try {
    rawResponse      = execution.execute(wrappedRequest, body)
    httpStatusGroup  = classify(rawResponse.statusCode()) → "2xx"/"4xx"/"5xx"
    buffered         = new BufferingClientHttpResponse(rawResponse, maxBodyBytes)
    result           = buffered.peek(responseCodeExtractor) → ResponseCodeResult
    callLogger.log(sourceName, destinationName, result)
    outboundCallMetrics.record(destinationName, result.outcome(), httpStatusGroup)
    return buffered
  } catch (Exception e when rawResponse != null) {
    // instrumentation error — degrade gracefully
    callLogger.logWarn(sourceName, destinationName, e)
    outboundCallMetrics.record(destinationName, Outcome.ABSENT, httpStatusGroup)
    return rawResponse   // original response, unmodified
  }
  // IOException from execution.execute() (rawResponse == null) propagates normally
```

**`BufferingClientHttpResponse` body contract**:
- `peek()`: calls `delegate.getBody().readNBytes(maxBodyBytes)` → stores in `cachedBody`
  - If bytes read < maxBodyBytes: body fits; `getBody()` → `new ByteArrayInputStream(cachedBody)` (re-readable)
  - If bytes read == maxBodyBytes AND one more byte exists: oversized; `getBody()` → `new SequenceInputStream(new ByteArrayInputStream(cachedBody), delegate.getBody())` — full body available; result = ABSENT
  - Any exception during `readNBytes`: result = ABSENT; `getBody()` returns original delegate stream

### WebClient Path

```
filter(request, next):
  sourceName      ← resolver.sourceName
  destinationName ← resolver.resolve(request)
  mutatedRequest  ← ClientRequest.from(request).header(source).header(dest).build()

  next.exchange(mutatedRequest)
    .flatMap(response → instrumentResponse(response, sourceName, destinationName))
    .onErrorResume(t → {
        callLogger.log(source, dest, ABSENT)
        metrics.record(dest, ABSENT, "network-error")
        return Mono.error(t)   // re-propagate; business call still fails
    })

instrumentResponse(response, source, dest):
  statusGroup  ← classify(response.statusCode())
  cachedBody   ← response.bodyToFlux(DataBuffer.class).cache()  // multicast hot publisher

  sideEffect ← DataBufferUtils.join(
                  DataBufferUtils.takeUntilByteCount(cachedBody, maxBodyBytes + 1),
                  maxBodyBytes)
               .map(buf → try { read bytes; release; return extractor.extract(bytes) }
                          catch { return Optional.empty() })
               .defaultIfEmpty(Optional.empty())
               .onErrorReturn(Optional.empty())
               .doOnNext(optCode → { callLogger.log(source, dest, toResult(optCode));
                                     metrics.record(dest, ..., statusGroup) })
               .then()

  return sideEffect.thenReturn(response.mutate().body(cachedBody).build())
```

### Destination Name Resolution

```
resolve(request):
  hintHeader ← request.headers().getFirst(props.serviceNameHintHeader)
  if hintHeader is non-blank → return hintHeader    // Priority 1: explicit hint

  uri  ← request.getURI()
  host ← uri.getHost()                              // hostname or IP address
  port ← uri.getPort()
  if port == -1: port = "https".equals(uri.getScheme()) ? 443 : 80
  return host + ":" + port                          // Priority 2: host:port
```

### Metrics Recording

```java
Counter.builder(props.getMetrics().getPrefix() + ".total")
  .tag(props.getMetrics().getDestinationTagName(), destination)
  .tag(props.getMetrics().getOutcomeTagName(), outcome.label())
  .tag(props.getMetrics().getStatusGroupTagName(), httpStatusGroup)
  .register(meterRegistry)
  .increment();
// Counter.builder().register() is idempotent — safe to call per request
```

Prometheus name: `http_outbound_calls_total` (dots → underscores; `_total` suffix added by Micrometer).

## Test Strategy

### Non-Intrusion Regression Suite (`@Tag("non-intrusion")` — CI merge gate)

| Test | Class | Assertion |
|---|---|---|
| 1 | `NonIntrusionRestTemplateTest` | Body byte-for-byte identical to uninstrumented response |
| 2 | `NonIntrusionRestTemplateTest` | Extractor exception → call succeeds; `responseCode=absent` |
| 3 | `NonIntrusionRestTemplateTest` | Logger exception → call succeeds |
| 4 | `NonIntrusionRestTemplateTest` | Empty body → call succeeds; `responseCode=absent` |
| 5 | `NonIntrusionRestTemplateTest` | Non-JSON body → call succeeds; `responseCode=absent` |
| 6 | `NonIntrusionRestTemplateTest` | Body > 1 MB → call succeeds; full body intact; `responseCode=absent` |
| 7 | `NonIntrusionWebClientTest` | Body byte-for-byte identical to uninstrumented response |
| 8 | `NonIntrusionWebClientTest` | Extractor exception → call succeeds; `responseCode=absent` |
| 9 | `NonIntrusionWebClientTest` | Empty body → call succeeds; `responseCode=absent` |
| 10 | `NonIntrusionWebClientTest` | Body > 1 MB → call succeeds; full body intact; `responseCode=absent` |
| 11 | `NonIntrusionWebClientTest` | Network error → re-propagated to caller; `network-error` metric recorded |

### Auto-Configuration Matrix (100% coverage required)

`ApplicationContextRunner` tests covering:
- `RestTemplate` on classpath + enabled + no override → `OutboundCallInterceptor` registered
- `RestTemplate` absent → `OutboundCallInterceptor` NOT registered
- `WebClient` on classpath + enabled + no override → `OutboundCallExchangeFilter` registered
- `WebClient` absent → `OutboundCallExchangeFilter` NOT registered
- `enabled=false` → zero starter beans in context
- Consumer provides `ResponseCodeExtractor` → `JacksonResponseCodeExtractor` NOT instantiated
- Consumer provides `DestinationNameResolver` → default NOT instantiated
- `MeterRegistry` absent → `OutboundCallMetrics` NOT instantiated

### Integration Tests

All integration tests use JDK `com.sun.net.httpserver.HttpServer` (zero extra dependency):
- `RestTemplateIntegrationTest`: full stack; headers on wire; response code parsing; metric labels
- `WebClientIntegrationTest`: full stack; reactive chain; same assertions
- `MetricsIntegrationTest`: counter increments; multiple destinations tracked independently
- `SourceNameWarningTest`: no `spring.application.name` → "unknown" in header + WARN at startup
- `DataHygieneTest`: request with `Authorization: Bearer secret` → log output contains no "secret"

## Complexity Tracking

*No violations requiring justification. All design choices reflect the simplest mechanism satisfying the spec.*

**Version**: 1.0.0-SNAPSHOT | **Date**: 2026-08-27 | **Spec**: [spec.md](spec.md)
