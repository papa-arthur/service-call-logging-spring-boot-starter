# Data Model: Outbound HTTP Observability Starter

**Feature**: `specs/001-outbound-http-observability/spec.md`
**Created**: 2026-08-27

---

## Entities

### `ServiceCallLoggingProperties`

Configuration properties bound from the `service-call-logging.*` prefix.

| Field | Type | Default | Constraint |
|---|---|---|---|
| `enabled` | `boolean` | `true` | — |
| `sourceHeaderName` | `String` | `"X-Source-Service"` | `@NotBlank` |
| `destinationHeaderName` | `String` | `"X-Destination-Service"` | `@NotBlank` |
| `serviceNameHintHeader` | `String` | `"service_name"` | `@NotBlank` |
| `maxBodyBytes` | `int` | `1048576` (1 MB) | `@Min(0)` |
| `metrics` | `Metrics` (nested record) | see below | `@Valid` |

**Nested record `Metrics`**:

| Field | Type | Default | Constraint |
|---|---|---|---|
| `prefix` | `String` | `"http.outbound.calls"` | `@NotBlank` |
| `destinationTagName` | `String` | `"destination"` | `@NotBlank` |
| `outcomeTagName` | `String` | `"outcome"` | `@NotBlank` |
| `statusGroupTagName` | `String` | `"http_status_group"` | `@NotBlank` |

**Notes**:
- `@ConfigurationProperties(prefix = "service-call-logging")` with `@Validated`
- `Metrics` is an inner record or static nested class

---

### `ResponseCodeResult`

Immutable value object carrying the extracted `responseCode` plus its interpretation. Passed from instrumentation to `CallLogger` and `OutboundCallMetrics`.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `outcome` | `Outcome` | No | Always set |
| `rawCode` | `Integer` | Yes | `null` when `outcome == ABSENT` |

**Factory methods**:
- `ResponseCodeResult.of(int rawCode)` — creates SUCCESS (rawCode == 0) or FAILURE (rawCode != 0); `rawCode` stored as-is
- `ResponseCodeResult.ABSENT` — static constant; `outcome = ABSENT`, `rawCode = null`

**Validation rules**:
- `rawCode` is meaningful only when `outcome != ABSENT`
- Consumers MUST check `outcome` before reading `rawCode`

---

### `Outcome`

Enum representing the interpreted result of a call's `responseCode`.

| Value | `label()` | Condition |
|---|---|---|
| `SUCCESS` | `"success"` | `rawCode == 0` |
| `FAILURE` | `"failure"` | `rawCode != null && rawCode != 0` |
| `ABSENT` | `"absent"` | Body missing, non-JSON, no `responseCode` field, body > cap, or extractor exception |

**Usage**: `label()` is used as the value of the `outcome` Micrometer tag.

---

### `OutboundCallRecord`

Value object assembled inside instrumentation paths and passed to `CallLogger`. Never persisted or serialised.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `source` | `String` | No | `spring.application.name` or `"unknown"` |
| `destination` | `String` | No | `service_name` header or `host:port` |
| `httpMethod` | `String` | No | e.g., `"GET"`, `"POST"` |
| `httpStatusCode` | `Integer` | Yes | `null` on network error |
| `httpStatusGroup` | `String` | No | `"2xx"` / `"4xx"` / `"5xx"` / `"network-error"` |
| `responseCode` | `Integer` | Yes | `null` when outcome is ABSENT |
| `timestamp` | `Instant` | No | Set at start of `intercept()` / `filter()` |

**Notes**:
- `CallLogger` formats this into a single structured SLF4J log entry
- No PII fields; `Authorization` and `Cookie` headers MUST NOT appear here (enforced by ArchUnit `DataHygieneArchTest`)

---

### `ResponseCodeExtractor` (SPI interface)

Consumer-replaceable strategy for extracting `responseCode` from response body bytes.

```
@FunctionalInterface
interface ResponseCodeExtractor {
    Optional<Integer> extract(byte[] bodyBytes);
}
```

**Contract**:
- MUST return `Optional.empty()` for any unparseable input — MUST NOT throw
- MUST be pure: no side effects observable outside the method
- MUST return within a bounded time (no blocking I/O, no network calls)
- Return value is used to build `ResponseCodeResult`: `Optional.of(0)` → SUCCESS; `Optional.of(n≠0)` → FAILURE; `Optional.empty()` → ABSENT

See `contracts/response-code-extractor-spi.md` for full contract and examples.

---

## State Transitions

### Per-call lifecycle (RestTemplate path)

```
[START intercept()]
    │
    ▼
[Resolve source + destination names]
    │
    ▼
[Stamp headers on outgoing request]
    │
    ▼
[httpStatusGroup = "network-error"]  ← pessimistic initialisation
    │
    ▼
[execution.execute()] ──── IOException ──────────────────────────► [propagate; no telemetry recorded]
    │ (success)
    ▼
[httpStatusGroup = classify(statusCode)]
    │
    ▼
[BufferingClientHttpResponse.peek(extractor)]
    │                     │
    │ (≤ cap)             │ (> cap OR extractor exception)
    ▼                     ▼
[ResponseCodeResult]   [ResponseCodeResult.ABSENT]
    │
    ▼
[callLogger.log(record)]
[outboundCallMetrics.record(dest, outcome, httpStatusGroup)]
    │
    ▼
[return buffered response to caller]
```

### Per-call lifecycle (WebClient path)

```
[START filter()]
    │
    ▼
[Resolve source + destination names]
    │
    ▼
[Mutate request: add correlation headers]
    │
    ▼
[next.exchange(mutatedRequest)]
    │              │
    │ (network     │ (HTTP response)
    │  error)      ▼
    │         [instrumentResponse()]
    │              │
    │              ▼
    │         [cache() body flux]
    │              │
    │              ├──[side-channel: takeUntilByteCount → join → extract]
    │              │       │
    │              │       ▼
    │              │   [callLogger.log() + metrics.record()]
    │              │
    │              └──[response.mutate().body(cachedBody) → return to caller]
    │
    ▼
[onErrorResume: log ABSENT + metrics "network-error"; re-throw]
```

---

## Relationship Diagram (informal)

```
ServiceCallLoggingProperties
    └── Metrics (nested)

ServiceCallLoggingAutoConfiguration
    ├── DestinationNameResolver        (reads spring.application.name)
    ├── JacksonResponseCodeExtractor   implements ResponseCodeExtractor
    ├── CallLogger                     uses OutboundCallRecord
    ├── OutboundCallMetrics            uses Outcome, ResponseCodeResult
    ├── RestTemplateInstrumentationConfiguration
    │       ├── OutboundCallInterceptor  implements ClientHttpRequestInterceptor
    │       │       └── BufferingClientHttpResponse  implements ClientHttpResponse
    │       └── RestTemplateCustomizer (lambda bean)
    └── WebClientInstrumentationConfiguration
            ├── OutboundCallExchangeFilter  implements ExchangeFilterFunction
            └── WebClientCustomizer (lambda bean)

OutboundCallInterceptor / OutboundCallExchangeFilter
    │ builds
    ▼
OutboundCallRecord  ──► CallLogger
ResponseCodeResult  ──► OutboundCallMetrics
```
