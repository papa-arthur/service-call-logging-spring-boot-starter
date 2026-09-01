# Tasks: Outbound HTTP Observability Starter

**Input**: Design documents from `specs/001-outbound-http-observability/`

**Prerequisites**: [plan.md](plan.md) ✅ | [spec.md](spec.md) ✅ | [research.md](research.md) ✅ | [data-model.md](data-model.md) ✅ | [contracts/](contracts/) ✅

**TDD Gate**: Constitution Principle V mandates test-first. Tests in each phase MUST be written and observed to FAIL before the corresponding implementation tasks are started.

**Format**: `[ID] [P?] [Story?] Description — file path`

- **[P]**: Parallelizable (different files, no pending dependencies)
- **[Story]**: User story label — mandatory in Phase 3+

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Maven project skeleton — all subsequent phases depend on this being complete.

- [X] T001 Create `pom.xml` with coordinates (`com.bookit:service-call-logging-spring-boot-starter:1.0.0-SNAPSHOT`, parent `spring-boot-starter-parent:3.3.13`), full dependency layout from plan.md (always/optional/test/annotationProcessor scopes), and plugin stubs for `maven-compiler-plugin`, `maven-surefire-plugin`, `japicmp-maven-plugin` — `pom.xml`
- [X] T002 [P] Create main source package tree: `src/main/java/com/bookit/servicecalllogging/` with sub-packages `autoconfigure/`, `interceptor/`, `filter/`, `resolver/`, `extractor/`, `logging/`, `metrics/`
- [X] T003 [P] Create test source package tree: `src/test/java/com/bookit/servicecalllogging/` with sub-packages `autoconfigure/`, `interceptor/`, `filter/`, `resolver/`, `extractor/`, `logging/`, `metrics/`, `security/`
- [X] T004 Create Spring Boot 3 auto-configuration registration file with single entry — `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Checkpoint**: `./mvnw compile` must succeed before proceeding.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared value objects, SPI, resolver, and logger that every user story's instrumentation code depends on. No user story phase may begin until this phase is complete.

**⚠️ CRITICAL**: These components are imported by both the blocking (RestTemplate) and reactive (WebClient) instrumentation paths. All must be complete before Phase 3.

> **TDD**: Write each test task first, run `./mvnw test` to confirm RED, then implement.

- [X] T005 Write unit tests for `Outcome` (label() values for SUCCESS/FAILURE/ABSENT) and `ResponseCodeResult` (of(0)→SUCCESS, of(1)→FAILURE, ABSENT constant, nullable rawCode) — `src/test/java/com/bookit/servicecalllogging/metrics/OutcomeTest.java`, `ResponseCodeResultTest.java`
- [X] T006 Implement `Outcome.java` (enum: SUCCESS, FAILURE, ABSENT; `label()` returns "success"/"failure"/"absent") — `src/main/java/com/bookit/servicecalllogging/metrics/Outcome.java`
- [X] T007 [P] Implement `ResponseCodeResult.java` (record: `Outcome outcome`, `@Nullable Integer rawCode`; static `ABSENT` constant; `of(int rawCode)` factory: 0→SUCCESS, else FAILURE) — `src/main/java/com/bookit/servicecalllogging/metrics/ResponseCodeResult.java`
- [X] T008 [P] Implement `ResponseCodeExtractor.java` (public `@FunctionalInterface` SPI: `Optional<Integer> extract(byte[] bodyBytes)`; no implementation — interface only) — `src/main/java/com/bookit/servicecalllogging/ResponseCodeExtractor.java`
- [X] T008b Write `ServiceCallLoggingPropertiesTest.java` — assert all 9 default values bind correctly from an empty configuration source; assert `@NotBlank` rejects a blank `sourceHeaderName`; assert `@Min(0)` rejects `maxBodyBytes=-1`; assert nested `Metrics` record defaults are accessible via `getMetrics()` — `src/test/java/com/bookit/servicecalllogging/ServiceCallLoggingPropertiesTest.java`
- [X] T009 Implement `ServiceCallLoggingProperties.java` (`@ConfigurationProperties(prefix="service-call-logging")`, `@Validated`; all 9 keys with defaults; nested `Metrics` record with 4 keys; `@NotBlank` on Strings, `@Min(0)` on `maxBodyBytes`, `@Valid` on `metrics`) — `src/main/java/com/bookit/servicecalllogging/ServiceCallLoggingProperties.java`
- [X] T009a Write `OutboundCallRecordTest.java` — assert constructor sets all seven fields (`source`, `destination`, `httpMethod`, `httpStatusCode`, `httpStatusGroup`, `responseCode`, `timestamp`); assert all accessors return the expected values; assert `timestamp` is not null; assert nullable fields (`httpStatusCode`, `responseCode`) accept null without error — `src/test/java/com/bookit/servicecalllogging/logging/OutboundCallRecordTest.java`
- [X] T009b Implement `OutboundCallRecord.java` (immutable value object used by `CallLogger`; fields: `String source`, `String destination`, `String httpMethod`, `@Nullable Integer httpStatusCode`, `String httpStatusGroup`, `@Nullable Integer responseCode`, `Instant timestamp`; constructor + accessors; no persistence) — `src/main/java/com/bookit/servicecalllogging/logging/OutboundCallRecord.java`
- [X] T010 Write `DestinationNameResolverTest.java` — all resolution cases: `service_name` header present → use as-is; header absent → `host:port`; host is IP → `ip:port`; port -1 with http scheme → append `:80`; port -1 with https scheme → append `:443`; null host → returns "unknown" — `src/test/java/com/bookit/servicecalllogging/resolver/DestinationNameResolverTest.java`
- [X] T011 Implement `DestinationNameResolver.java` (reads `spring.application.name` for `sourceName`; logs WARN at construction if absent and sets `sourceName = "unknown"`; `resolve(URI, HttpHeaders)` follows priority: serviceNameHintHeader → host:port with scheme-based port default) — `src/main/java/com/bookit/servicecalllogging/resolver/DestinationNameResolver.java`
- [X] T012 [P] Write `JacksonResponseCodeExtractorTest.java` — cases: `{"responseCode":0}` → `Optional.of(0)`; `{"responseCode":1}` → `Optional.of(1)`; `{"responseCode":99}` → `Optional.of(99)`; missing field → `Optional.empty()`; non-JSON bytes → `Optional.empty()`; empty array → `Optional.empty()`; wrong type (string) → `Optional.empty()`; extractor MUST NOT throw — `src/test/java/com/bookit/servicecalllogging/extractor/JacksonResponseCodeExtractorTest.java`
- [X] T013 [P] Implement `JacksonResponseCodeExtractor.java` (plain class — no conditional annotations on the class itself; `@ConditionalOnClass(name="com.fasterxml.jackson.databind.ObjectMapper")` and `@ConditionalOnMissingBean(ResponseCodeExtractor.class)` are placed on the `@Bean` factory method in `ServiceCallLoggingAutoConfiguration`, not here; `ObjectMapper.readTree(bodyBytes).get("responseCode")`; returns `Optional.empty()` on any exception or missing/wrong-type field; never throws) — `src/main/java/com/bookit/servicecalllogging/extractor/JacksonResponseCodeExtractor.java`
- [X] T014 Write `CallLoggerTest.java` — asserts log entry contains source, destination, httpMethod, httpStatusCode, httpStatusGroup, and responseCode fields; asserts log entry does NOT contain the word "Authorization", "Cookie", or any bearer token value passed in a test header — `src/test/java/com/bookit/servicecalllogging/logging/CallLoggerTest.java`
- [X] T015 Implement `CallLogger.java` (SLF4J `LoggerFactory.getLogger`; `log(OutboundCallRecord)` emits single INFO entry with only: source, destination, method, status, statusGroup, responseCode/absent; `logWarn(source, destination, Throwable)` for instrumentation errors; no credential fields ever referenced) — `src/main/java/com/bookit/servicecalllogging/logging/CallLogger.java`

**Checkpoint**: `./mvnw test` must pass for all Phase 2 tests (T005, T010, T012, T014 test classes) before Phase 3 begins.

---

## Phase 3: User Story 1 — Zero-Touch Instrumentation (Priority: P1) 🎯 MVP

**Goal**: Adding the starter to any Spring Boot 3 service automatically stamps every outbound HTTP call (via RestTemplate or WebClient) with `X-Source-Service` and `X-Destination-Service` headers, reads `responseCode` from the JSON response envelope, and logs it — with zero code changes to the consuming service and byte-for-byte response body integrity.

**Independent Test**: Add starter to a minimal Spring Boot 3 service making one outbound call; assert (a) outgoing request carries `X-Source-Service` and `X-Destination-Service` headers; (b) log output contains source, destination, and `responseCode` (or `absent`); (c) response body bytes received by the calling code are identical to the bytes sent by the target server.

### Tests — RestTemplate Path (write first, confirm FAIL before T027)

- [X] T016 [P] [US1] Write `BufferingClientHttpResponseTest.java` — body ≤ cap: `getBody()` returns fresh `ByteArrayInputStream` with original bytes on every call (detected by one extra `read()` returning -1 after `readNBytes(cap)`); body > cap: `getBody()` returns `SequenceInputStream` delivering ALL original bytes including the overflow byte (detected by one extra `read()` returning a byte value); exception during peek: `peek()` returns `ABSENT`, delegate stream returned from `getBody()` unmodified — `src/test/java/com/bookit/servicecalllogging/interceptor/BufferingClientHttpResponseTest.java`
- [X] T017 [P] [US1] Write `NonIntrusionRestTemplateTest.java` — 6 tests tagged `@Tag("non-intrusion")`; each uses a JDK `HttpServer` stub and a real `RestTemplate`; tests: (1) body byte-for-byte identical; (2) extractor throws → call succeeds, `responseCode=absent`; (3) logger throws → call succeeds; (4) empty body → call succeeds, `responseCode=absent`; (5) non-JSON body → call succeeds, `responseCode=absent`; (6) body > 1 MB → call succeeds, full body bytes intact, `responseCode=absent` — `src/test/java/com/bookit/servicecalllogging/interceptor/NonIntrusionRestTemplateTest.java`
- [X] T018 [P] [US1] Write `OutboundCallInterceptorTest.java` — unit tests: `X-Source-Service` and `X-Destination-Service` headers stamped on wrapped request; `ResponseCodeResult` forwarded to `CallLogger`; extractor returning `Optional.empty()` → `ABSENT` logged — **do not reference `OutboundCallMetrics` here**; null-safe metrics wiring is tested in Phase 4 after T037 creates the class — `src/test/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptorTest.java`
- [X] T019 [P] [US1] Write `RestTemplateIntegrationTest.java` — full-stack test using JDK `HttpServer` stub and Spring Boot `ApplicationContextRunner`; assert: correlation headers on wire; `responseCode=0` → SUCCESS logged; `responseCode=1` → FAILURE logged; empty body → ABSENT logged; server-side 200, 400, 500 responses → correct `httpStatusGroup` logged — `src/test/java/com/bookit/servicecalllogging/interceptor/RestTemplateIntegrationTest.java`
- [X] T020 [P] [US1] Write `RestTemplateConfigurationActivationTest.java` — `ApplicationContextRunner`: RestTemplate on classpath (test scope) → `OutboundCallInterceptor` bean present; `RestTemplateCustomizer` bean present; repeat without RestTemplate on path if possible via `withClassLoader(FilteredClassLoader)` → neither bean present — `src/test/java/com/bookit/servicecalllogging/autoconfigure/RestTemplateConfigurationActivationTest.java`

### Tests — WebClient Path (write first, confirm FAIL before T029)

- [X] T021 [P] [US1] Write `NonIntrusionWebClientTest.java` — 5 tests tagged `@Tag("non-intrusion")`; each uses JDK `HttpServer` and a real `WebClient`; tests: (1) body byte-for-byte identical; (2) extractor throws → call succeeds, `responseCode=absent`; (3) empty body → call succeeds, `responseCode=absent`; (4) body > 1 MB → call succeeds, full body bytes intact, `responseCode=absent`; (5) network error (connection refused) → exception re-propagated to caller unchanged — `src/test/java/com/bookit/servicecalllogging/filter/NonIntrusionWebClientTest.java`
- [X] T022 [P] [US1] Write `OutboundCallExchangeFilterTest.java` — integration tests using JDK `HttpServer` stub (same pattern as RestTemplate integration tests; zero extra dependencies): mutated outgoing request carries correlation headers; `Flux.cache()` re-emits all body buffers to the final caller subscriber; extractor exception → `ABSENT` result forwarded to logger; `onErrorResume` re-propagates network errors unchanged to the WebClient caller — `src/test/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilterTest.java`
- [X] T023 [P] [US1] Write `WebClientIntegrationTest.java` — full-stack via JDK `HttpServer`; same structural assertions as `RestTemplateIntegrationTest`: headers on wire, outcome logging, `httpStatusGroup` accuracy — `src/test/java/com/bookit/servicecalllogging/filter/WebClientIntegrationTest.java`
- [X] T024 [P] [US1] Write `WebClientConfigurationActivationTest.java` — `ApplicationContextRunner` with `FilteredClassLoader`: WebClient present → `OutboundCallExchangeFilter` and named `WebClientCustomizer` beans present; WebClient absent → neither bean present — `src/test/java/com/bookit/servicecalllogging/autoconfigure/WebClientConfigurationActivationTest.java`

### Tests — Auto-Configuration (write first, confirm FAIL before T032)

- [X] T025 [P] [US1] Write `ServiceCallLoggingAutoConfigurationTest.java` — `ApplicationContextRunner` cases: default (enabled=true, RestTemplate+WebClient on path) → all 8 beans present; `DestinationNameResolver` + `CallLogger` + `JacksonResponseCodeExtractor` always present when enabled; no `spring.application.name` → `DestinationNameResolver` present with `sourceName = "unknown"` — `src/test/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfigurationTest.java`
- [X] T026 [P] [US1] Write `SourceNameWarningTest.java` — boot a context with no `spring.application.name` property; capture log output; assert WARN-level message contains text indicating source service name is unconfigured; assert `X-Source-Service: unknown` header appears on outgoing call — `src/test/java/com/bookit/servicecalllogging/autoconfigure/SourceNameWarningTest.java`

### Implementation — RestTemplate Path

- [X] T027 [US1] Implement `BufferingClientHttpResponse.java` (implements `ClientHttpResponse`; constructor takes delegate + `maxBodyBytes`; `peek(ResponseCodeExtractor)`: call `delegate.getBody().readNBytes(maxBodyBytes)` → `cachedBody`; then call one additional `delegate.getBody().read()` to detect overflow: if `-1` body fits — `getBody()` returns `new ByteArrayInputStream(cachedBody)` (re-readable); if byte `b` body is oversized — `getBody()` returns `new SequenceInputStream(new ByteArrayInputStream(concat(cachedBody, b)), delegate.getBody())` (all original bytes preserved); any exception during peek → return `ABSENT` + `getBody()` returns original delegate stream; all other `ClientHttpResponse` methods delegate unchanged) — `src/main/java/com/bookit/servicecalllogging/interceptor/BufferingClientHttpResponse.java`
- [X] T028 [P] [US1] Implement `OutboundCallInterceptor.java` (implements `ClientHttpRequestInterceptor`; constructor: `DestinationNameResolver`, `ResponseCodeExtractor`, `CallLogger`, `ServiceCallLoggingProperties` — **do NOT include `OutboundCallMetrics` in this constructor**; `OutboundCallMetrics` does not exist until Phase 4 (T037) and is wired in by T038; `intercept()`: stamp headers via `HttpRequestWrapper`; pessimistic `httpStatusGroup = "network-error"`; execute → classify status → `BufferingClientHttpResponse.peek()` → log → return buffered; catch instrumentation exception when rawResponse≠null → logWarn + return rawResponse unmodified; IOException from execute propagates) — `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java`
- [X] T029 [US1] Implement `RestTemplateInstrumentationConfiguration.java` (`@Configuration(proxyBeanMethods=false)`; `@ConditionalOnClass(name="org.springframework.web.client.RestTemplate")` string form; `OutboundCallInterceptor` bean `@ConditionalOnMissingBean`; named `RestTemplateCustomizer` bean `@ConditionalOnMissingBean(name="serviceCallLoggingRestTemplateCustomizer")` adding interceptor to `restTemplate.getInterceptors()`) — `src/main/java/com/bookit/servicecalllogging/autoconfigure/RestTemplateInstrumentationConfiguration.java`

### Implementation — WebClient Path

- [X] T030 [P] [US1] Implement `OutboundCallExchangeFilter.java` (implements `ExchangeFilterFunction`; `filter(request, next)`: resolve source/destination → mutate request with headers via `ClientRequest.from()` → `next.exchange(mutated).flatMap(instrumentResponse).onErrorResume(t → log ABSENT + `Mono.error(t)`)` — **do NOT call `OutboundCallMetrics` here**; `OutboundCallMetrics` does not exist until Phase 4 (T037) and is wired in by T039; `instrumentResponse()`: `statusGroup = classify(status)`; `cachedBody = response.bodyToFlux(DataBuffer.class).cache()`; side-channel: `DataBufferUtils.join(DataBufferUtils.takeUntilByteCount(cachedBody, cap+1), cap).map(buf→extract).defaultIfEmpty(empty).onErrorReturn(empty).doOnNext(log only, no metrics).then()`; return `sideEffect.thenReturn(response.mutate().body(cachedBody).build())`) — `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java`
- [X] T031 [P] [US1] Implement `WebClientInstrumentationConfiguration.java` (`@Configuration(proxyBeanMethods=false)`; `@ConditionalOnClass(name="org.springframework.web.reactive.function.client.WebClient")` string form; `OutboundCallExchangeFilter` bean `@ConditionalOnMissingBean`; named `WebClientCustomizer` bean `@ConditionalOnMissingBean(name="serviceCallLoggingWebClientCustomizer")` calling `builder.filter(exchangeFilter)`) — `src/main/java/com/bookit/servicecalllogging/autoconfigure/WebClientInstrumentationConfiguration.java`

### Implementation — Root Auto-Configuration

- [X] T032 [US1] Implement `ServiceCallLoggingAutoConfiguration.java` (`@AutoConfiguration`; `@ConditionalOnProperty(prefix="service-call-logging", name="enabled", havingValue="true", matchIfMissing=true)`; `@EnableConfigurationProperties(ServiceCallLoggingProperties.class)`; `@Import({RestTemplateInstrumentationConfiguration.class, WebClientInstrumentationConfiguration.class})`; Phase 3 beans only: `DestinationNameResolver` `@ConditionalOnMissingBean`, `@Bean` for `JacksonResponseCodeExtractor` with `@ConditionalOnClass(name="com.fasterxml.jackson.databind.ObjectMapper")` + `@ConditionalOnMissingBean(ResponseCodeExtractor.class)` on the method, `CallLogger` `@ConditionalOnMissingBean`; **do NOT add `OutboundCallMetrics` bean here** — that is added in Phase 4 task T038b after `OutboundCallMetrics.java` exists) — `src/main/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfiguration.java`
- [X] T033 [US1] Run Non-Intrusion regression suite and confirm all 11 tests GREEN: `./mvnw test -Dgroups=non-intrusion`

**Checkpoint**: User Story 1 fully functional. `./mvnw test` GREEN. Non-Intrusion gate GREEN. US1 independent test criteria met.

---

## Phase 4: User Story 2 — Outbound Call Outcome Metrics (Priority: P2)

**Goal**: Every instrumented call increments a Micrometer counter labelled by `destination`, `outcome` (success/failure/absent), and `http_status_group` (2xx/4xx/5xx/network-error), scraped via `/actuator/prometheus` for Grafana dashboards and alerts.

**Independent Test**: Make calls with `responseCode=0`, `responseCode=1`, and empty body to two distinct destinations; query `/actuator/prometheus`; verify `http_outbound_calls_total` counters exist with correct `destination`, `outcome`, and `http_status_group` label combinations and matching counts.

### Tests (write first, confirm FAIL before T037)

- [X] T034 [US2] Write `OutboundCallMetricsTest.java` — unit tests: `record("svc:8080", SUCCESS, "2xx")` → counter `http_outbound_calls_total{destination="svc:8080",outcome="success",http_status_group="2xx"}` incremented; different destinations tracked independently; configurable tag names used; `@ConditionalOnBean(MeterRegistry.class)` verified by absent-MeterRegistry case — `src/test/java/com/bookit/servicecalllogging/metrics/OutboundCallMetricsTest.java`
- [X] T035 [P] [US2] Write `MetricsIntegrationTest.java` — full-stack via JDK `HttpServer`; make 2× success calls to `dest-A`, 1× failure call to `dest-A`, 1× success call to `dest-B`; assert counter values; assert `network-error` label recorded when server is unreachable; assert Prometheus output format matches schema in `contracts/metrics-schema.md` — `src/test/java/com/bookit/servicecalllogging/metrics/MetricsIntegrationTest.java`
- [X] T036 [P] [US2] Write `MetricsAbsentWhenNoRegistryTest.java` — `ApplicationContextRunner` without `spring-boot-starter-actuator`; assert `OutboundCallMetrics` bean is absent; assert `OutboundCallInterceptor` still present (metrics dependency optional) — `src/test/java/com/bookit/servicecalllogging/autoconfigure/MetricsAbsentWhenNoRegistryTest.java`

### Implementation

- [X] T037 [US2] Implement `OutboundCallMetrics.java` (`@ConditionalOnBean(MeterRegistry.class)`; `@ConditionalOnMissingBean`; `record(String destination, Outcome outcome, String httpStatusGroup)`: calls `Counter.builder(props.getMetrics().getPrefix() + ".total").tag(destTagName, destination).tag(outcomeTagName, outcome.label()).tag(statusGroupTagName, httpStatusGroup).register(meterRegistry).increment()`; idempotent) — `src/main/java/com/bookit/servicecalllogging/metrics/OutboundCallMetrics.java`
- [X] T038b [US2] Add `OutboundCallMetrics` `@Bean` declaration to `ServiceCallLoggingAutoConfiguration.java` (now that `OutboundCallMetrics.java` exists from T037; add `@Bean @ConditionalOnBean(MeterRegistry.class) @ConditionalOnMissingBean(OutboundCallMetrics.class)` factory method; inject `ServiceCallLoggingProperties` + `MeterRegistry`) — `src/main/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfiguration.java`
- [X] T038 [US2] Wire `OutboundCallMetrics` (optional) into `OutboundCallInterceptor.java` — **cross-story edit: modifies the US1 artifact created by T028; requires T037 to be complete before starting**: add `@Nullable OutboundCallMetrics outboundCallMetrics` as a new constructor parameter; add `if (outboundCallMetrics != null) outboundCallMetrics.record(...)` calls in the try-block after log and in the instrumentation-error catch; wrap metrics recording in its own try/catch to prevent any metrics failure from surfacing — `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java`
- [X] T039 [P] [US2] Wire `OutboundCallMetrics` (optional) into `OutboundCallExchangeFilter.java`: inject via `@Nullable`; call `outboundCallMetrics.record(...)` inside `doOnNext` side effect and inside `onErrorResume`; wrap in null-safe guard — `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java`
- [X] T040 [US2] Verify `http_outbound_calls_total` Prometheus output matches schema in `contracts/metrics-schema.md`: run `MetricsIntegrationTest` and inspect captured Prometheus text; confirm label names, value types, and HELP/TYPE lines are correct

**Checkpoint**: User Story 2 complete. All metrics tests GREEN. Prometheus output verified against schema.

---

## Phase 5: User Story 3 — Graceful Handling of Non-Standard Responses (Priority: P3)

**Goal**: Every non-standard response — empty body, non-JSON body, JSON without `responseCode` field, or body exceeding the 1 MB cap — results in `responseCode=absent` logged, the original response returned intact to the caller, and no exception surfacing into business logic.

**Independent Test**: Assert all four degradation cases independently for both RestTemplate and WebClient paths: (1) empty body → ABSENT + call succeeds; (2) non-JSON body → ABSENT + full body returned; (3) valid JSON without `responseCode` field → ABSENT + full JSON returned; (4) body > `max-body-bytes` → ABSENT + full body returned (not truncated).

### Tests (write first, confirm FAIL before T043)

- [X] T041 [US3] Add test case to `JacksonResponseCodeExtractorTest.java` (or a separate `GracefulDegradationExtractorTest.java`): JSON `{"status":"OK"}` (no `responseCode` field) → `Optional.empty()`; JSON `{"responseCode":null}` → `Optional.empty()`; JSON `{"responseCode":"zero"}` (wrong type) → `Optional.empty()` — `src/test/java/com/bookit/servicecalllogging/extractor/JacksonResponseCodeExtractorTest.java`
- [X] T042 [P] [US3] Write `DataHygieneTest.java` — integration test: outbound request carries `Authorization: Bearer supersecret` header; after instrumented call, capture SLF4J log output; assert no log message contains "supersecret" or "Authorization" — `src/test/java/com/bookit/servicecalllogging/logging/DataHygieneTest.java`

### Implementation / Verification

- [X] T043 [US3] Verify `JacksonResponseCodeExtractor.java` returns `Optional.empty()` for all three cases in T041; fix if any case throws or returns non-empty — `src/main/java/com/bookit/servicecalllogging/extractor/JacksonResponseCodeExtractor.java`
- [X] T044 [P] [US3] Verify `BufferingClientHttpResponse.java` oversized path: body > `maxBodyBytes` → `SequenceInputStream(ByteArrayInputStream(cachedPrefix), delegate.getBody())` delivers ALL original bytes to caller; write byte-level assertion in T016 or add to `BufferingClientHttpResponseTest.java` if missing — `src/test/java/com/bookit/servicecalllogging/interceptor/BufferingClientHttpResponseTest.java`
- [X] T045 [P] [US3] Verify `OutboundCallExchangeFilter.java` oversized reactive path: `DataBufferUtils.takeUntilByteCount` caps the side-channel read but `cachedBody` (the `cache()` publisher) still re-emits ALL original buffers to the caller; verify via `NonIntrusionWebClientTest` test 4 (body > 1 MB) — `src/test/java/com/bookit/servicecalllogging/filter/NonIntrusionWebClientTest.java`
- [X] T046 [US3] Run full Non-Intrusion regression suite post-US3 to confirm no regressions: `./mvnw test -Dgroups=non-intrusion` — all 11 tests must remain GREEN

**Checkpoint**: All 4 degradation cases verified for both paths. Non-Intrusion gate still GREEN.

---

## Phase 6: User Story 4 — Starter Configuration & Override (Priority: P4)

**Goal**: Platform engineers can customise correlation header names via properties, replace `responseCode` extraction with their own bean, and disable the starter entirely — all without modifying starter source code.

**Independent Test**: (1) Set `service-call-logging.source-header-name=X-From` in `application.yml`; make a call; assert `X-From` header present on outgoing request, not `X-Source-Service`. (2) Register a custom `ResponseCodeExtractor` bean; assert `JacksonResponseCodeExtractor` is NOT in the context. (3) Set `service-call-logging.enabled=false`; start the service; assert zero starter beans in context and zero headers/logs/metrics from any call.

### Tests (write first, confirm FAIL before T051)

- [X] T047 [US4] Write `ResponseCodeExtractorOverrideTest.java` — `ApplicationContextRunner` with a custom `ResponseCodeExtractor` bean declared; assert `JacksonResponseCodeExtractor` is NOT in context; assert custom bean IS in context; assert `OutboundCallInterceptor` uses the custom extractor — `src/test/java/com/bookit/servicecalllogging/autoconfigure/ResponseCodeExtractorOverrideTest.java`
- [X] T048 [P] [US4] Write `DestinationResolverOverrideTest.java` — `ApplicationContextRunner` with a custom `DestinationNameResolver` bean; assert starter's default `DestinationNameResolver` is NOT in context; assert custom bean IS in context — `src/test/java/com/bookit/servicecalllogging/autoconfigure/DestinationResolverOverrideTest.java`
- [X] T049 [P] [US4] Write `DisabledStarterTest.java` — `ApplicationContextRunner` with `service-call-logging.enabled=false`; assert context contains zero beans from `com.bookit.servicecalllogging` package (no `OutboundCallInterceptor`, `OutboundCallExchangeFilter`, `CallLogger`, `OutboundCallMetrics`, etc.) — `src/test/java/com/bookit/servicecalllogging/autoconfigure/DisabledStarterTest.java`
- [X] T050 [P] [US4] Write `CustomHeaderNamesTest.java` — integration test with `service-call-logging.source-header-name=X-From-Service` and `destination-header-name=X-To-Service`; make call via JDK `HttpServer`; assert `X-From-Service` and `X-To-Service` headers present on wire; assert `X-Source-Service` and `X-Destination-Service` absent — `src/test/java/com/bookit/servicecalllogging/autoconfigure/CustomHeaderNamesTest.java`

### Implementation / Verification

- [X] T051 [US4] Audit all `@ConditionalOnMissingBean` annotations in `ServiceCallLoggingAutoConfiguration.java`, `RestTemplateInstrumentationConfiguration.java`, `WebClientInstrumentationConfiguration.java`: every starter bean must have it; fix any missing annotations; confirm T047 + T048 pass — `src/main/java/com/bookit/servicecalllogging/autoconfigure/`
- [X] T052 [P] [US4] Verify `@ConditionalOnProperty(prefix="service-call-logging", name="enabled", havingValue="true", matchIfMissing=true)` on root `@AutoConfiguration` prevents all beans when `enabled=false`; confirm `DisabledStarterTest` (T049) passes — `src/main/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfiguration.java`
- [X] T053 [P] [US4] Verify all **class-level** `@ConditionalOnClass` annotations on `@Configuration` classes (`RestTemplateInstrumentationConfiguration.java`, `WebClientInstrumentationConfiguration.java`) use the string `name=` form (NOT `.class` literal) — this prevents `NoClassDefFoundError` when optional libs are absent; note: `@Bean`-method-level `@ConditionalOnClass` annotations may safely use `.class` literal form (Spring evaluates conditions before calling the factory method); grep class declarations specifically — `src/main/java/com/bookit/servicecalllogging/autoconfigure/`
- [X] T054 [US4] Run full auto-configuration matrix: `./mvnw test -Dtest="*AutoConfigurationTest,*ActivationTest,*OverrideTest,*DisabledStarterTest,*CustomHeaderNamesTest"` — 100% coverage required (Constitution Principle V)

**Checkpoint**: All user stories complete. All override, disable, and custom-config scenarios verified. Full test suite GREEN.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Security gate, CI configuration, binary compat gate, and end-to-end quickstart validation. Applies across all user stories.

- [X] T055 Write `DataHygieneArchTest.java` using ArchUnit: assert no method in packages `com.bookit.servicecalllogging.interceptor`, `com.bookit.servicecalllogging.filter`, `com.bookit.servicecalllogging.logging` calls any logging method with a string argument that contains the literal `"Authorization"` or `"Cookie"` — `src/test/java/com/bookit/servicecalllogging/security/DataHygieneArchTest.java`
- [X] T056 [P] Configure `maven-surefire-plugin` in `pom.xml` with a dedicated execution (id: `non-intrusion-gate`) that runs `@Tag("non-intrusion")` tests and fails the build if any fail; configure second execution for remaining tests — `pom.xml`
- [X] T057 [P] Configure `japicmp-maven-plugin` in `pom.xml` with `<skip>true</skip>` for the initial `1.0.0` release (no baseline artifact exists yet); gate activates on MINOR/PATCH releases once `1.0.0` is published by switching to `<skip>false</skip>` and setting `<oldVersion>1.0.0</oldVersion>` with `<breakBuildOnBinaryIncompatibleModifications>true</breakBuildOnBinaryIncompatibleModifications>` — add a comment in `pom.xml` explaining the activation condition — `pom.xml`
- [X] T058 [P] Configure `spring-boot-configuration-processor` in `pom.xml` `annotationProcessorPaths` section to generate `additional-spring-configuration-metadata.json` IDE completion metadata — `pom.xml`
- [X] T058b [P] Write `README.md` covering: all 9 configuration keys with types, defaults, and effects (linking to `contracts/configuration.md`); `ResponseCodeExtractor` SPI with a concrete replacement example (linking to `contracts/response-code-extractor-spi.md`); Prometheus metric schema with example PromQL queries (linking to `contracts/metrics-schema.md`); **per-call instrumentation overhead budget** — document worst-case nanosecond/microsecond cost for the blocking path (RestTemplate interceptor) and the reactive path (WebClient filter), expressed as a bounded upper estimate per call (Constitution Principle VI requires this to be documented); both known limitations stated verbatim per Constitution Principle VIII: (a) "HTTP clients constructed outside Spring's standard builder mechanisms are not instrumented"; (b) "Distributed-trace and log-correlation propagation on the reactive path is the consuming service's responsibility"; quickstart link to `specs/001-outbound-http-observability/quickstart.md` — `README.md`
- [X] T059 Run `./mvnw verify` — full build: compile, all tests (including non-intrusion gate and ArchUnit), japicmp check; fix any remaining failures
- [X] T060 Follow `specs/001-outbound-http-observability/quickstart.md` end-to-end against a minimal test Spring Boot 3 service: add starter dependency; run; validate all 8 steps (headers, logs, metrics, Grafana queries, override examples, disable switch); confirm all expected outcomes are met

**Checkpoint**: `./mvnw verify` GREEN. Quickstart validation complete. Feature ready for `/speckit-implement` or PR.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — **BLOCKS all user story phases**
- **User Story 1 (Phase 3)**: Depends on Phase 2 — no other story dependency
- **User Story 2 (Phase 4)**: Depends on Phase 2 + Phase 3 (metrics wired into interceptor/filter)
- **User Story 3 (Phase 5)**: Depends on Phase 2 + Phase 3 (verifies existing degradation behavior)
- **User Story 4 (Phase 6)**: Depends on Phase 2 + Phase 3 (overrides tested against auto-config)
- **Polish (Phase 7)**: Depends on all user story phases

### User Story Dependencies

- **US1 (P1)**: No story-level dependency — first to implement
- **US2 (P2)**: Depends on US1 (metrics wired into `OutboundCallInterceptor` and `OutboundCallExchangeFilter`)
- **US3 (P3)**: Depends on US1 (validates degradation behavior in existing code; no new production files)
- **US4 (P4)**: Depends on US1 (override tests target auto-configuration beans created in US1)

### Within Each Phase

- TDD gate: test tasks must be written and FAIL before implementation begins
- For RestTemplate and WebClient paths: can be implemented in parallel after shared foundations are done
- `ServiceCallLoggingAutoConfiguration` (T032) depends on `RestTemplateInstrumentationConfiguration` (T029) and `WebClientInstrumentationConfiguration` (T031)
- `OutboundCallInterceptor` (T028) depends on `BufferingClientHttpResponse` (T027); T028 and T030 can run in parallel once T027 is done
- `OutboundCallRecord` (T009b) must exist before `CallLogger` (T015) and `CallLoggerTest` (T014) can be written
- `OutboundCallMetrics` bean declaration in auto-config (T038b) depends on `OutboundCallMetrics.java` (T037)
- Metrics wiring (T038, T039) depends on `OutboundCallMetrics` (T037)

---

## Parallel Execution Examples

### Phase 3: User Story 1 — Test Writing (all independent files)

```bash
# Write these test files in parallel — all are in different files:
T017: NonIntrusionRestTemplateTest.java
T018: OutboundCallInterceptorTest.java
T019: RestTemplateIntegrationTest.java
T020: RestTemplateConfigurationActivationTest.java
T021: NonIntrusionWebClientTest.java
T022: OutboundCallExchangeFilterTest.java
T023: WebClientIntegrationTest.java
T024: WebClientConfigurationActivationTest.java
T025: ServiceCallLoggingAutoConfigurationTest.java
T026: SourceNameWarningTest.java
```

### Phase 3: User Story 1 — RestTemplate vs WebClient implementation

```bash
# After T027 (BufferingClientHttpResponse) is done — both are [P]:
T028: OutboundCallInterceptor.java          # RestTemplate path
T030: OutboundCallExchangeFilter.java       # WebClient path — parallel with T028

# After T028 + T030:
T029: RestTemplateInstrumentationConfiguration.java
T031: WebClientInstrumentationConfiguration.java   # parallel with T029
```

### Phase 4: User Story 2 — Tests

```bash
# Write in parallel:
T034: OutboundCallMetricsTest.java
T035: MetricsIntegrationTest.java
T036: MetricsAbsentWhenNoRegistryTest.java
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1 (RestTemplate + WebClient paths + auto-config)
4. **STOP and VALIDATE**: `./mvnw test -Dgroups=non-intrusion` → 11 tests GREEN
5. Demo: minimal service with starter → headers on wire + log entries + body intact

### Incremental Delivery

1. **Foundation** → Setup + Foundational → compile + unit tests GREEN
2. **US1** → Zero-touch instrumentation → Non-Intrusion gate GREEN (MVP)
3. **US2** → Metrics → Prometheus output verified against schema
4. **US3** → Graceful degradation → verified; no new production files expected
5. **US4** → Override scenarios → full auto-config matrix 100% coverage
6. **Polish** → ArchUnit, CI gate, japicmp, quickstart → `./mvnw verify` GREEN

### Parallel Team Strategy

With 2+ developers:

1. Both complete Phase 1 + Phase 2 together
2. After Phase 2:
   - Developer A: Phase 3 RestTemplate path (T016–T019, T027–T029)
   - Developer B: Phase 3 WebClient path (T021–T024, T030–T031)
3. Merge → T025, T026, T032, T033 (auto-config + CI gate)
4. Developer A: Phase 4 (Metrics)
5. Developer B: Phase 5 + Phase 6 (Graceful degradation + Override)
6. Both: Phase 7 (Polish)

---

## Notes

- `[P]` = different files, no pending dependencies — safe to run in parallel
- `[USn]` maps task to user story; Setup/Foundational/Polish tasks carry no story label
- TDD gate (Constitution Principle V): every test task MUST be run and observed RED before the corresponding implementation task begins — this is non-negotiable
- Non-Intrusion regression suite (`@Tag("non-intrusion")`) is a CI merge gate — `./mvnw test -Dgroups=non-intrusion` must stay GREEN at every phase checkpoint
- `@ConditionalOnClass(name=...)` string form ONLY — never `.class` literal on any configuration class (prevents `NoClassDefFoundError` with optional libs absent)
- JDK `com.sun.net.httpserver.HttpServer` for all integration test stubs — zero extra test dependency
- `ApplicationContextRunner` for all auto-configuration matrix tests — fast, no Spring context startup overhead
- Every commit should leave `./mvnw test` GREEN — no broken windows
