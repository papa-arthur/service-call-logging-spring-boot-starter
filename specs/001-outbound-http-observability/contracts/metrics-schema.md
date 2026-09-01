# Contract: Metrics Schema

**Feature**: `specs/001-outbound-http-observability/spec.md`
**Created**: 2026-08-27

---

## Metric Definition

| Field | Value |
|---|---|
| **Micrometer name** | `http.outbound.calls.total` (default prefix `http.outbound.calls` + `.total`) |
| **Prometheus name** | `http_outbound_calls_total` (Micrometer converts dots to underscores; appends `_total` for counters) |
| **Type** | Counter (monotonically increasing) |
| **Unit** | Calls (dimensionless) |
| **Registration** | Idempotent — re-registering with the same name + tag values returns the existing counter |

---

## Labels (Tags)

| Tag key | Default key name | Possible values | Description |
|---|---|---|---|
| `destination` | `destination` | any string | Destination service name: `service_name` header value if present; otherwise `host:port` derived from the request URI |
| `outcome` | `outcome` | `success` \| `failure` \| `absent` | Interpreted result of the `responseCode` field |
| `http_status_group` | `http_status_group` | `2xx` \| `4xx` \| `5xx` \| `network-error` | HTTP status code classification; `network-error` when no HTTP response was received |

Tag key names are configurable — see `contracts/configuration.md`.

---

## Label Value Semantics

### `outcome`

| Value | Meaning |
|---|---|
| `success` | `responseCode` was extracted and equals `0` |
| `failure` | `responseCode` was extracted and is non-zero |
| `absent` | `responseCode` could not be extracted: body was missing, empty, non-JSON, lacked the field, exceeded `max-body-bytes`, or the extractor threw an exception |

### `http_status_group`

| Value | HTTP status range |
|---|---|
| `2xx` | 200–299 |
| `4xx` | 400–499 |
| `5xx` | 500–599 |
| `network-error` | No HTTP response received (connection refused, timeout, DNS failure, etc.) |

---

## Label Cardinality Guidance

- `destination` has unbounded cardinality in theory. In practice, the number of unique destination services a single service calls is small (typically < 20). Monitor cardinality if destinations include user-supplied or path-derived values — in those cases, override `DestinationNameResolver` to normalize the label.
- `outcome` has fixed cardinality: 3 values.
- `http_status_group` has fixed cardinality: 4 values.
- Maximum time series per destination: 3 × 4 = 12.

---

## Example Prometheus Output

```
# HELP http_outbound_calls_total Outbound HTTP call counter
# TYPE http_outbound_calls_total counter
http_outbound_calls_total{destination="payments-service:8080",outcome="success",http_status_group="2xx"} 1432.0
http_outbound_calls_total{destination="payments-service:8080",outcome="failure",http_status_group="2xx"} 87.0
http_outbound_calls_total{destination="payments-service:8080",outcome="absent",http_status_group="5xx"} 12.0
http_outbound_calls_total{destination="auth-service:443",outcome="success",http_status_group="2xx"} 9801.0
http_outbound_calls_total{destination="auth-service:443",outcome="absent",http_status_group="network-error"} 3.0
```

---

## PromQL Reference

### Success rate per destination (last 5 minutes)

```promql
rate(http_outbound_calls_total{outcome="success"}[5m])
  /
rate(http_outbound_calls_total[5m])
```

### Failure count by destination (last 5 minutes, summed)

```promql
sum by (destination) (
  increase(http_outbound_calls_total{outcome="failure"}[5m])
)
```

### Total calls per destination per minute (last 15 minutes)

```promql
sum by (destination) (
  rate(http_outbound_calls_total[15m])
) * 60
```

### Network error rate alert rule

```promql
rate(http_outbound_calls_total{http_status_group="network-error"}[5m])
  /
rate(http_outbound_calls_total[5m])
  > 0.05
```

Alert fires when more than 5% of calls to any destination result in a network error over the past 5 minutes.

### Calls with absent `responseCode` (body parsing issues)

```promql
sum by (destination) (
  rate(http_outbound_calls_total{outcome="absent"}[5m])
)
```

---

## Grafana Dashboard Panels

| Panel | Query | Visualization |
|---|---|---|
| Success rate by destination | `rate(http_outbound_calls_total{outcome="success"}[5m]) / rate(http_outbound_calls_total[5m])` | Time series (%) |
| Failure count | `sum by (destination) (increase(http_outbound_calls_total{outcome="failure"}[$__rate_interval]))` | Bar chart |
| Call volume by destination | `sum by (destination) (rate(http_outbound_calls_total[$__rate_interval]))` | Time series |
| Network errors | `rate(http_outbound_calls_total{http_status_group="network-error"}[$__rate_interval])` | Time series |
| Status group breakdown | `sum by (http_status_group) (rate(http_outbound_calls_total[$__rate_interval]))` | Pie chart |

---

## Prerequisite

The metric is only registered when a `MeterRegistry` bean is present on the application context (i.e., `spring-boot-actuator` is on the classpath). If Actuator is absent, no metrics are recorded and no error is raised — the `OutboundCallMetrics` bean is simply not created (`@ConditionalOnBean(MeterRegistry.class)`).

Prometheus scraping requires `management.endpoints.web.exposure.include=prometheus` in `application.yml` and a Prometheus scrape configuration targeting `/actuator/prometheus`.
