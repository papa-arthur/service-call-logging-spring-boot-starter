# Contract: Configuration Properties

**Feature**: `specs/001-outbound-http-observability/spec.md`
**Created**: 2026-08-27

All properties are bound under the prefix `service-call-logging`.

---

## Property Reference

### Master Switch

| Key | Type | Default | Description |
|---|---|---|---|
| `service-call-logging.enabled` | `boolean` | `true` | Set to `false` to disable all instrumentation. When disabled, no headers are added, no logs are emitted, and no metrics are recorded. The starter remains on the classpath but contributes no active beans. |

---

### Header Names

| Key | Type | Default | Description |
|---|---|---|---|
| `service-call-logging.source-header-name` | `String` | `X-Source-Service` | Name of the request header stamped with the calling service's name (`spring.application.name`, or `"unknown"` if absent). |
| `service-call-logging.destination-header-name` | `String` | `X-Destination-Service` | Name of the request header stamped with the destination service name (`service_name` header value from the request, or `host:port` derived from the request URI). |
| `service-call-logging.service-name-hint-header` | `String` | `service_name` | Name of an inbound hint header on the *outgoing* request. If this header is already present when the interceptor/filter runs, its value is used as the destination name (highest priority). |

---

### Body Parsing

| Key | Type | Default | Description |
|---|---|---|---|
| `service-call-logging.max-body-bytes` | `int` | `1048576` (1 MB) | Maximum number of response body bytes read for `responseCode` extraction. Bytes beyond this cap are not buffered by the instrumentation. Set to `0` to skip body reading entirely (all calls report `responseCode=absent`). |

---

### Metrics

| Key | Type | Default | Description |
|---|---|---|---|
| `service-call-logging.metrics.prefix` | `String` | `http.outbound.calls` | Micrometer counter name prefix. The counter is registered as `<prefix>.total`. Dots are converted to underscores by Prometheus (`http_outbound_calls_total`). |
| `service-call-logging.metrics.destination-tag-name` | `String` | `destination` | Micrometer tag key used for the destination service name. |
| `service-call-logging.metrics.outcome-tag-name` | `String` | `outcome` | Micrometer tag key used for the call outcome (`success`, `failure`, or `absent`). |
| `service-call-logging.metrics.status-group-tag-name` | `String` | `http_status_group` | Micrometer tag key used for the HTTP status code group (`2xx`, `4xx`, `5xx`, or `network-error`). |

---

## Example: `application.yml` with All Defaults Shown

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

---

## Example: Custom Header Names

```yaml
service-call-logging:
  source-header-name: X-From-Service
  destination-header-name: X-To-Service
```

---

## Example: Disable the Starter

```yaml
service-call-logging:
  enabled: false
```

---

## Example: Skip Body Parsing (all calls report `responseCode=absent`)

```yaml
service-call-logging:
  max-body-bytes: 0
```

---

## Known Limitations

1. **HTTP clients constructed outside the Spring builder pipeline are not instrumented.**
   If a consumer constructs a `RestTemplate` with `new RestTemplate()` or a `WebClient` with `WebClient.create()` (instead of using an injected `RestTemplate` or `WebClient.Builder`), the starter's interceptor/filter is not applied and no telemetry is produced for calls made through that instance.

2. **Distributed-trace / log correlation on the reactive path is the consumer's responsibility.**
   The starter does not propagate MDC context or trace IDs across reactive boundaries. Consumers using the WebClient path who require correlated trace IDs in logs must configure their own `ContextSnapshot`-aware instrumentation (e.g., Micrometer Tracing + `ContextPropagation`).

3. **Responses larger than `max-body-bytes` produce `responseCode=absent`.**
   Only the first `max-body-bytes` bytes are read for JSON parsing. If the `responseCode` field falls beyond the cap, it is not found and the call is recorded as `responseCode=absent`. The full response body is still delivered to the caller unchanged.

4. **The `service_name` hint header is read from the *outgoing* request, not from the response.**
   If the consumer sets `service_name` on the request before invoking the HTTP client, that value takes priority as the destination name. If not set, the destination is derived from the request URI (`host:port`).
