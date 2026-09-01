# Service Call Logging Spring Boot Starter

Auto-configurable Spring Boot 3 starter that instruments **every outbound HTTP call** your
service makes. It stamps each request with source/destination correlation headers, reads the
`responseCode` field from the shared response envelope, logs it, and records Prometheus-ready
counters for Grafana dashboards and alerts.

It does all of that **without any code change in your service**, and — by design and by
enforced test gate — **without ever changing the outcome of the calls it observes**.

```xml
<dependency>
    <groupId>com.bookit</groupId>
    <artifactId>service-call-logging-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

That is the entire integration. Both `RestTemplate` and `WebClient` are supported, and each
activates only if that client is already on your classpath.

---

## Contents

- [What you get](#what-you-get)
- [Configuration reference](#configuration-reference)
- [What gets logged](#what-gets-logged)
- [Metrics and Grafana](#metrics-and-grafana)
- [Replacing the responseCode extractor](#replacing-the-responsecode-extractor)
- [Overriding anything else](#overriding-anything-else)
- [Turning it off](#turning-it-off)
- [Performance: per-call overhead budget](#performance-per-call-overhead-budget)
- [Known limitations](#known-limitations)
- [Guarantees](#guarantees)

---

## What you get

Set `spring.application.name` and make an outbound call. On the wire:

```
X-Source-Service: my-service
X-Destination-Service: payments-service:8080
```

In your logs:

```
INFO  c.b.s.logging.CallLogger - outbound-call source=my-service destination=payments-service:8080 \
      method=POST httpStatus=200 httpStatusGroup=2xx responseCode=0
```

At `/actuator/prometheus` (when Actuator is present):

```
# TYPE http_outbound_calls_total counter
http_outbound_calls_total{destination="payments-service:8080",outcome="success",http_status_group="2xx"} 1432.0
```

**Destination name resolution** follows this priority:

1. The value of the `service_name` header if the outgoing request already carries one
   (header name configurable) — used verbatim.
2. Otherwise `host:port` derived from the target URL. A missing port becomes `443` for
   `https` and `80` otherwise. An IP-address host is used as-is (`192.168.1.100:8080`).

If `spring.application.name` is not set, the source name is reported as `unknown` and a single
`WARN` is emitted at startup. Startup never fails because of it.

---

## Configuration reference

Every key is optional. All nine are listed here with their type, default and effect.

### Master switch

| Key | Type | Default | Effect |
|---|---|---|---|
| `service-call-logging.enabled` | `boolean` | `true` | `false` disables the starter entirely: no beans are registered, no headers are added, nothing is logged, no metrics are recorded. The dependency stays on the classpath and contributes nothing. |

### Correlation headers

| Key | Type | Default | Effect |
|---|---|---|---|
| `service-call-logging.source-header-name` | `String` | `X-Source-Service` | Name of the header stamped with the calling service's name. |
| `service-call-logging.destination-header-name` | `String` | `X-Destination-Service` | Name of the header stamped with the resolved destination service name. |
| `service-call-logging.service-name-hint-header` | `String` | `service_name` | Header read off the **outgoing** request. If present and non-blank, its value becomes the destination name and no URL derivation happens. |

### Body parsing

| Key | Type | Default | Effect |
|---|---|---|---|
| `service-call-logging.max-body-bytes` | `int` | `1048576` (1 MB) | Ceiling on response bytes read for `responseCode` extraction. Bodies larger than this are **not parsed** and report `responseCode=absent` — but are still delivered to your code in full. Set to `0` to skip body reading entirely. |

### Metrics

| Key | Type | Default | Effect |
|---|---|---|---|
| `service-call-logging.metrics.prefix` | `String` | `http.outbound.calls` | Micrometer counter name prefix; the counter is `<prefix>.total`. |
| `service-call-logging.metrics.destination-tag-name` | `String` | `destination` | Tag key for the destination service name. |
| `service-call-logging.metrics.outcome-tag-name` | `String` | `outcome` | Tag key for the call outcome. |
| `service-call-logging.metrics.status-group-tag-name` | `String` | `http_status_group` | Tag key for the HTTP status group. |

### Everything, with defaults shown

```yaml
service-call-logging:
  enabled: true
  source-header-name: X-Source-Service
  destination-header-name: X-Destination-Service
  service-name-hint-header: service_name
  max-body-bytes: 1048576
  metrics:
    prefix: http.outbound.calls
    destination-tag-name: destination
    outcome-tag-name: outcome
    status-group-tag-name: http_status_group
```

Full contract: [`contracts/configuration.md`](specs/001-outbound-http-observability/contracts/configuration.md).

---

## What gets logged

The set of logged fields is **fixed and exhaustive**. These six, and nothing else:

| Field | Meaning |
|---|---|
| `source` | Your `spring.application.name`, or `unknown` |
| `destination` | Resolved destination service name |
| `method` | HTTP method of the outgoing request |
| `httpStatus` | Received HTTP status code, or `none` on a network error |
| `httpStatusGroup` | `2xx` / `4xx` / `5xx` / `network-error` (also `1xx` / `3xx`) |
| `responseCode` | Parsed business code, or `absent` |

The starter **never** logs credentials, tokens, `Authorization` or `Cookie` header values, PII,
or request/response bodies. This is enforced statically: a build-time check fails if any
compiled class so much as carries a credential header name as a string constant, and an
architecture rule prevents the logging package from depending on header types at all.

Instrumentation failures log at `WARN` with only the exception type and message — never a
stack trace carrying request state.

---

## Metrics and Grafana

One counter, three tags. Requires Actuator on your classpath; without it, no metrics are
recorded and nothing fails.

| | |
|---|---|
| **Micrometer name** | `http.outbound.calls.total` |
| **Prometheus name** | `http_outbound_calls_total` |
| **Type** | Counter (monotonic) |

| Tag | Values |
|---|---|
| `destination` | resolved destination service name |
| `outcome` | `success` (responseCode 0) · `failure` (non-zero) · `absent` (unparseable) |
| `http_status_group` | `2xx` · `4xx` · `5xx` · `network-error` (also `1xx` · `3xx`) |

Expose the endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
```

**Success rate per destination**

```promql
rate(http_outbound_calls_total{outcome="success"}[5m])
  / rate(http_outbound_calls_total[5m])
```

**Failure count by destination**

```promql
sum by (destination) (increase(http_outbound_calls_total{outcome="failure"}[$__rate_interval]))
```

**Call volume per destination, per minute**

```promql
sum by (destination) (rate(http_outbound_calls_total[$__rate_interval])) * 60
```

**Alert — network error rate above 5%**

```promql
rate(http_outbound_calls_total{http_status_group="network-error"}[5m])
  / rate(http_outbound_calls_total[5m]) > 0.05
```

Cardinality is bounded at 12 time series per destination (3 outcomes × 4 common status groups).
If your destinations are user- or path-derived, override `DestinationNameResolver` to normalise
them before they become labels.

Full schema: [`contracts/metrics-schema.md`](specs/001-outbound-http-observability/contracts/metrics-schema.md).

---

## Replacing the responseCode extractor

The default reads a **top-level integer** `responseCode` from a JSON body. If your envelope
differs, declare your own bean — the default is `@ConditionalOnMissingBean`, so yours wins
automatically and the default is never created.

For an envelope that nests the code under `data`:

```java
@Configuration
public class ExtractorConfig {

    @Bean
    public ResponseCodeExtractor nestedResponseCodeExtractor(ObjectMapper mapper) {
        return bodyBytes -> {
            try {
                JsonNode code = mapper.readTree(bodyBytes).path("data").path("responseCode");
                return code.isInt() ? Optional.of(code.intValue()) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        };
    }
}
```

Your implementation **must**:

1. **Never throw** — return `Optional.empty()` for anything unparseable.
2. **Never return null** — use `Optional.empty()` for absence.
3. **Be pure** — no I/O, no logging, no shared-state mutation.
4. **Return in bounded time** — no blocking calls or locks.
5. **Treat `bodyBytes` as read-only** — the array is shared.

The starter is defensive about all five, but a violation costs you telemetry.

`Optional.of(0)` → `success`; `Optional.of(n≠0)` → `failure`; `Optional.empty()` → `absent`.
The raw integer is logged whatever it is, so you can correlate log lines with specific business
error codes.

Full contract: [`contracts/response-code-extractor-spi.md`](specs/001-outbound-http-observability/contracts/response-code-extractor-spi.md).

---

## Overriding anything else

Every bean the starter contributes is `@ConditionalOnMissingBean`. Declare your own bean of the
same type and yours is used instead:

| Bean | Why you might replace it |
|---|---|
| `ResponseCodeExtractor` | Non-standard response envelope |
| `DestinationNameResolver` | Normalise destination names to control metric cardinality |
| `CallLogger` | Change the log format, or emit structured JSON |
| `OutboundCallMetrics` | Custom tagging |
| `OutboundCallInterceptor` | Full control of the blocking path |
| `OutboundCallExchangeFilter` | Full control of the reactive path |
| `serviceCallLoggingRestTemplateCustomizer` (by name) | Change how the interceptor is attached |
| `serviceCallLoggingWebClientCustomizer` (by name) | Change how the filter is attached |

---

## Turning it off

```yaml
service-call-logging:
  enabled: false
```

No beans, no headers, no logs, no metrics — an emergency off-ramp that does not require
removing the dependency or rebuilding against a different artifact.

---

## Performance: per-call overhead budget

Measured on the blocking path with the transport stubbed out, so the figure is purely the
starter's own work — name resolution, header stamping, body buffering, JSON extraction, logging
and the counter increment:

| Body size | Overhead per call |
|---|---|
| 52 bytes (typical JSON envelope) | **~3.3–3.8 µs** |

For context, that is roughly **three to four orders of magnitude below** a typical network round
trip (1–100 ms). Cost scales with body size up to `max-body-bytes` and stops there: past the cap
nothing is parsed at all.

Worst-case budget to plan against: **under 10 µs per call** for envelopes up to a few KB.
If you need to re-measure on your own hardware:

```bash
mvn test-compile
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
java -cp "target/classes:target/test-classes:$(cat cp.txt)" \
  com.bookit.servicecalllogging.OverheadBenchmark
```

Memory is bounded too: the starter copies at most `max-body-bytes` for parsing and stops
copying once that ceiling is reached.

---

## Known limitations

These are stated up front so you never discover them in production.

1. **HTTP clients constructed outside Spring's standard builder mechanisms are not
   instrumented.** If you build a client with `new RestTemplate()` or `WebClient.create()`
   instead of injecting `RestTemplateBuilder` / `WebClient.Builder` and calling `.build()`, the
   customizer never runs, and no headers, logs or metrics are produced for calls through that
   instance. Inject the builder to get instrumentation.

2. **Distributed-trace and log-correlation propagation on the reactive path is the consuming
   service's responsibility.** The starter does not propagate MDC context or trace IDs across
   reactive boundaries. If you need correlated trace IDs on WebClient log entries, configure
   Micrometer Tracing with context propagation yourself.

3. **Responses larger than `max-body-bytes` report `responseCode=absent`.** Only the first
   `max-body-bytes` are read. If the field falls past the cap it is not found. The full body
   still reaches your code unchanged. Raise `max-body-bytes` if you need those codes.

4. **The `service_name` hint is read from the outgoing request, not the response.** Set it
   before invoking the client if you want to control the destination label directly.

5. **WebClient's own `spring.codec.max-in-memory-size` (256 KB by default) is separate from
   this starter's `max-body-bytes`.** A response larger than the codec limit fails inside
   WebClient before the starter is involved — exactly as it would without the starter. Raise
   the codec limit if you handle large reactive responses.

---

## Guarantees

The starter is governed by a project constitution; these are the guarantees it exists to make,
each backed by an automated gate:

- **Non-intrusion.** Instrumentation never alters the outcome, contract or body of a call it
  observes. Any failure inside the instrumentation path — a parse error, a broken extractor, a
  failing logger, a metrics error — degrades to reduced telemetry, never a failed or altered
  call. A dedicated 16-test regression suite enforces this as a CI merge gate, including
  byte-for-byte body identity and full delivery of oversized bodies.
- **Zero forced footprint.** `spring-web`, `spring-webflux` and `jackson-databind` are optional
  dependencies. Nothing is forced onto your dependency graph beyond the starter artifact, and
  each path activates only when that client is already present. A service with neither HTTP
  client — or without Jackson, or without Actuator — starts normally.
- **Fully overridable.** Zero code changes required; every bean replaceable; one switch to
  disable.
- **Data hygiene.** The logged field set is fixed, exhaustive and statically enforced.
- **Semantic versioning.** Configuration keys are never silently renamed or removed within a
  major version; breaking changes bump MAJOR and ship a migration note.

---

## Requirements

- Java 17+
- Spring Boot 3.x
- Actuator (optional — required only for metrics)
- Jackson (optional — required only for the default extractor)

## Validating an integration

Step-by-step end-to-end validation guide:
[`quickstart.md`](specs/001-outbound-http-observability/quickstart.md).
