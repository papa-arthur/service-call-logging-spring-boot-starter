# Research: Outbound HTTP Observability Starter

**Feature**: `specs/001-outbound-http-observability/spec.md`
**Created**: 2026-08-27
**Status**: Complete — all decisions finalised before plan.md was written

---

## Decision Log

### D-001 — Spring Boot Version

**Decision**: Spring Boot 3.3.x (latest stable 3.x line)

**Rationale**: 3.3.x is the current LTS-aligned stable release, fully compatible with Java 17, and uses the new `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` registration mechanism. Spring Boot 2.x is end-of-life. Spring Boot 3.4+ alpha streams are not stable enough for a library depended on by multiple teams.

**Alternatives considered**:
- Spring Boot 2.7.x — EOL; uses `spring.factories`; would limit consumers to Java 11/17 without Java 21 pathway. Rejected.
- Spring Boot 3.4.x — pre-release at plan time; API churn risk for a library that enforces binary compatibility (Principle IV). Deferred.

---

### D-002 — Auto-Configuration Registration Mechanism

**Decision**: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (one fully-qualified class name per line)

**Rationale**: Spring Boot 3 deprecated `spring.factories` for auto-configuration. The new file is the canonical mechanism from Spring Boot 3.0 onward. Using the old file works but triggers a deprecation warning that would surface in consumer logs — violating Principle I (no observable change to consumers).

**Alternatives considered**:
- `META-INF/spring.factories` under `org.springframework.boot.autoconfigure.EnableAutoConfiguration` — still functional but deprecated, generates startup warning. Rejected.

---

### D-003 — RestTemplate Interception Extension Point

**Decision**: `ClientHttpRequestInterceptor` registered via `RestTemplateCustomizer`

**Rationale**: `ClientHttpRequestInterceptor` is the documented, stable Spring extension point for request/response interception in RestTemplate. `RestTemplateCustomizer` is the auto-configuration idiom for adding behaviour to all `RestTemplate` beans created by `RestTemplateBuilder`. Zero consumer code change required — satisfies Principle II.

**Alternatives considered**:
- `ClientHttpRequestFactory` wrapping — interceptor chain is simpler and composes well with existing consumer interceptors. Rejected.
- `HandlerInterceptor` — server-side only; irrelevant. Rejected.
- `RestClientCustomizer` (Spring 6.1 `RestClient`) — separate client type; out of scope for this spec. Deferred to a future spec.

---

### D-004 — WebClient Interception Extension Point

**Decision**: `ExchangeFilterFunction` registered via `WebClientCustomizer`

**Rationale**: `ExchangeFilterFunction` is the documented reactive extension point for `WebClient`. `WebClientCustomizer` applies the filter to all `WebClient.Builder`-constructed clients, requiring zero consumer code change. Satisfies Principle I (reactive errors degrade to absent telemetry; business call unaffected) and Principle II.

**Alternatives considered**:
- `WebFilter` — server-side Webflux filter; does not intercept outbound calls. Rejected.
- Wrapping `WebClient` — would require consumers to use our `WebClient` type; violates Principle II and III. Rejected.

---

### D-005 — Body Re-Readability on Blocking Path

**Decision**: Custom `BufferingClientHttpResponse` using `readNBytes(maxBodyBytes)` + `SequenceInputStream` for oversized responses

**Rationale**: Spring's built-in `BufferingClientHttpResponseWrapper` reads the entire body into memory with no upper bound, violating the 1 MB cap (Principle VI — bounded cost). Our custom wrapper reads at most `maxBodyBytes` bytes from the delegate, stores them in `byte[] cachedBody`, and serves subsequent `getBody()` calls with a fresh `ByteArrayInputStream(cachedBody)`. For responses larger than the cap, `SequenceInputStream` reconstitutes the full stream from the cached prefix + the remaining delegate body — the business caller receives the complete, untruncated response (Principle I: body fully readable).

**Alternatives considered**:
- `BufferingClientHttpResponseWrapper` — unbounded buffer; violates Principle VI. Rejected.
- Limit reads at the HTTP client level — not possible via `ClientHttpRequestInterceptor`; requires `ClientHttpRequestFactory` replacement, which is a heavier footprint. Rejected.
- Discard body above cap — body would be truncated for caller; violates Principle I. Rejected.

---

### D-006 — Reactive Body Peek Without Double-Subscription

**Decision**: `originalBodyFlux.cache()` to create a hot multicasting publisher; `DataBufferUtils.takeUntilByteCount(cachedBody, cap+1)` into `DataBufferUtils.join(...)` for the side-channel; `response.mutate().body(cachedBody)` to re-emit all buffers to the caller

**Rationale**: A cold `Flux<DataBuffer>` from WebClient can only be subscribed to once — subscribing twice would attempt two separate HTTP reads, which is impossible on a socket already consumed. `cache()` turns the cold flux hot and multicasts to all subscribers. The side-channel (`DataBufferUtils.join(takeUntilByteCount(...))`) reads at most cap bytes for JSON parsing without consuming the full body. The caller's subscriber receives the cached (multicasted) buffers. `DataBuffer` reference counting: `DataBufferUtils.join` releases the joined composite; individual cached buffers are managed by the `cache()` operator's internal subscription.

**Alternatives considered**:
- `collectList()` on the body — accumulates all buffers; no size limit; violates Principle VI. Rejected.
- Reading bytes inside a `flatMap` before forwarding — causes double-subscription on a cold flux. Rejected.
- `Sinks.Many` multicasting — more complex and lower-level than `cache()`; same semantics. Rejected.

---

### D-007 — JSON Parsing for `responseCode` Extraction

**Decision**: `ObjectMapper.readTree(bytes).get("responseCode")` as the default `JacksonResponseCodeExtractor`

**Rationale**: `jackson-databind` is already on the classpath for any `spring-boot-starter-web` or `spring-boot-starter-webflux` consumer. The `readTree` approach avoids binding to a domain type (no additional classes created in consumer's classpath scan). The `@ConditionalOnClass(ObjectMapper.class)` guard ensures the bean is absent when Jackson is not present, satisfying Principle II.

**Alternatives considered**:
- Bundling a minimal JSON parser — extra dependency; violates Principle II. Rejected.
- `JsonPath` — additional dependency; overkill for reading a single field. Rejected.
- Regex on raw bytes — fragile and incorrect for non-ASCII or nested JSON. Rejected.

---

### D-008 — Metrics Library

**Decision**: Micrometer `Counter.builder(...).tag(...).register(meterRegistry)` — idempotent registration

**Rationale**: Micrometer is included transitively by `spring-boot-actuator`, which the vast majority of Spring Boot services already use. `Counter.builder().register(meterRegistry)` is idempotent: calling it multiple times with the same name+tags returns the existing counter rather than creating a duplicate. Prometheus format is served via Actuator's `/actuator/prometheus` endpoint — zero extra dependency. `@ConditionalOnBean(MeterRegistry.class)` ensures the metrics bean is absent when Actuator is not on the classpath.

**Alternatives considered**:
- Micrometer `Gauge` — appropriate for point-in-time values, not for counting calls. Rejected.
- Prometheus Java client directly — duplicates what Micrometer already provides; adds transitive dependency. Rejected.
- Custom in-memory counter — no Grafana/Prometheus integration out of the box. Rejected.

---

### D-009 — HTTP Stub in Integration Tests

**Decision**: JDK `com.sun.net.httpserver.HttpServer` (built-in, no import required)

**Rationale**: Zero transitive test dependency. Sufficient to stub HTTP responses with controlled status codes, headers, and bodies for all Non-Intrusion and integration test scenarios. Available on all JDK 11+ distributions.

**Alternatives considered**:
- WireMock — widely used but adds a test dependency. Acceptable but unnecessary given the simplicity of the stubs needed. Rejected in favour of zero footprint.
- MockWebServer (OkHttp) — brings OkHttp dependency into test scope. Rejected.
- `MockRestServiceServer` (Spring) — works for RestTemplate but not for WebClient integration tests. Rejected for dual-path coverage.

---

### D-010 — Binary Compatibility Gate

**Decision**: `japicmp` Maven plugin configured to fail the build on incompatible API changes for MINOR and PATCH releases

**Rationale**: Constitution Principle IV mandates binary compatibility within a major version. `japicmp` compares the current artifact against the previous release artifact and reports removed/changed public members. A failed `japicmp` check is a build error — no silent regressions.

**Alternatives considered**:
- Manual code review — error-prone; misses subtle binary-incompatible changes (e.g., adding a default method to an interface, changing a covariant return type). Rejected.
- `Revapi` — similar capability; `japicmp` has better Maven plugin documentation and wider Spring ecosystem adoption. Rejected.

---

### D-011 — `@ConditionalOnClass` Form

**Decision**: String `name=` form (`@ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")`) exclusively on all root and inner `@Configuration` classes that guard optional paths

**Rationale**: Using the `.class` literal form (e.g., `@ConditionalOnClass(RestTemplate.class)`) causes the JVM to attempt to load `RestTemplate.class` when the configuration class is processed — even before the condition is evaluated — resulting in `NoClassDefFoundError` when `spring-web` is absent from the classpath. The `name=` form defers class loading to the condition evaluation itself, which succeeds and returns `false` without error. This is a known Spring Boot pitfall documented in the reference guide.

**Alternatives considered**:
- `.class` literal — causes `NoClassDefFoundError` when optional lib absent. Rejected.
- Separate Maven module per client type — would force consumers to add multiple starters. Rejected.
