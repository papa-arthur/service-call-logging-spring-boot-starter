# Quickstart Validation Guide: Outbound HTTP Observability Starter

**Feature**: `specs/001-outbound-http-observability/spec.md`
**Created**: 2026-08-27

This guide walks through end-to-end validation that the starter works correctly after integration. Follow each step in order; each section has a clear expected outcome.

---

## Prerequisites

- Java 17 or later
- Maven 3.9+
- A Spring Boot 3.x service that makes outbound HTTP calls (using `RestTemplate` or `WebClient`)
- `spring-boot-starter-actuator` on the classpath
- A Prometheus instance configured to scrape `/actuator/prometheus`
- `curl` and `grep` available on the command line

---

## Step 1 — Add the Dependency

Add the starter to `pom.xml`:

```xml
<dependency>
    <groupId>com.bookit</groupId>
    <artifactId>service-call-logging-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Expose the Prometheus endpoint in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
spring:
  application:
    name: my-service
```

No other configuration is required. The starter auto-configures itself.

---

## Step 2 — Start the Application

```bash
./mvnw spring-boot:run
```

**Expected**: Application starts without errors. No new startup errors or warnings should appear related to `service-call-logging` (a `WARN` is expected only if `spring.application.name` is absent — see Step 2a).

### Step 2a — Verify Source Service Name (optional)

If `spring.application.name` is set in `application.yml`, the source header value will be that name.

If `spring.application.name` is **not** set, check the startup log for:

```
WARN  c.a.s.ServiceCallLoggingAutoConfiguration - spring.application.name is not configured; source service name will be reported as "unknown". Set spring.application.name to suppress this warning.
```

This is expected behaviour. Set `spring.application.name` to eliminate the warning.

---

## Step 3 — Make an Outbound Call

Trigger any outbound HTTP call from the running service (e.g., via a test endpoint, Postman, or curl against the consuming service's own API).

Alternatively, use `curl` to hit an endpoint in your service that internally calls another service.

---

## Step 4 — Validate Outgoing Request Headers

To confirm that correlation headers are stamped on outgoing requests, proxy the outbound call through `curl -v` or inspect the headers received by the downstream service.

For a quick test using a public echo service (replace with your own test endpoint):

```bash
curl -v http://localhost:8080/your-endpoint-that-calls-external-service 2>&1 | grep -i "X-Source\|X-Destination"
```

**Expected**: The downstream service (or echo endpoint) receives:

```
X-Source-Service: my-service
X-Destination-Service: target-host:port
```

Where `target-host:port` is derived from the outgoing request URI, or the value of the `service_name` header if your service sets it explicitly.

---

## Step 5 — Validate Logs

Grep the application logs for the structured log entry emitted after each instrumented call.

```bash
# In a second terminal, watch logs for responseCode entries
./mvnw spring-boot:run 2>&1 | grep "responseCode"
```

**Expected log format** (exact field names depend on your SLF4J appender configuration):

```
INFO  c.a.s.logging.CallLogger - source=my-service destination=payments-service:8080 method=POST httpStatus=200 httpStatusGroup=2xx responseCode=0
```

Three representative cases to validate:

| Scenario | Expected `responseCode` | Expected `outcome` |
|---|---|---|
| Response body contains `{"responseCode": 0, ...}` | `0` | `success` |
| Response body contains `{"responseCode": 1, ...}` | `1` | `failure` |
| Response body is non-JSON or lacks `responseCode` field | absent | `absent` |

---

## Step 6 — Validate Metrics

After making at least one outbound call, scrape the Prometheus endpoint:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep http_outbound_calls
```

**Expected output** (values and labels will vary):

```
# HELP http_outbound_calls_total
# TYPE http_outbound_calls_total counter
http_outbound_calls_total{destination="payments-service:8080",http_status_group="2xx",outcome="success"} 1.0
```

Verify:
- The metric name is `http_outbound_calls_total`
- Labels `destination`, `outcome`, and `http_status_group` are all present
- `outcome` value is one of: `success`, `failure`, `absent`
- `http_status_group` value is one of: `2xx`, `4xx`, `5xx`, `network-error`

---

## Step 7 — Validate Grafana Queries

Import the following PromQL queries into Grafana panels to verify data flows end-to-end:

**Panel 1: Success rate per destination**

```promql
rate(http_outbound_calls_total{outcome="success"}[5m])
  /
rate(http_outbound_calls_total[5m])
```

Expected: A value between 0 and 1 for each destination with recorded calls.

**Panel 2: Failure count by destination**

```promql
sum by (destination) (
  increase(http_outbound_calls_total{outcome="failure"}[$__rate_interval])
)
```

Expected: Zero if all calls succeeded; non-zero if any call returned a non-zero `responseCode`.

**Panel 3: Call volume per destination**

```promql
sum by (destination) (
  rate(http_outbound_calls_total[$__rate_interval])
) * 60
```

Expected: Number of calls per minute per destination.

**Alert rule — network error rate exceeds 5%:**

```promql
rate(http_outbound_calls_total{http_status_group="network-error"}[5m])
  /
rate(http_outbound_calls_total[5m])
  > 0.05
```

---

## Step 8 — Validate Override Behaviours

### 8a — Custom Header Names

Add to `application.yml`:

```yaml
service-call-logging:
  source-header-name: X-From-Service
  destination-header-name: X-To-Service
```

Repeat Step 4. Expected: downstream service receives `X-From-Service` and `X-To-Service` instead of the defaults.

### 8b — Custom `ResponseCodeExtractor`

If your response envelope nests `responseCode` differently (e.g., `{"data": {"responseCode": 0}}`), register a custom extractor bean:

```java
@Bean
public ResponseCodeExtractor customExtractor(ObjectMapper mapper) {
    return bodyBytes -> {
        try {
            JsonNode root = mapper.readTree(bodyBytes);
            JsonNode code = root.path("data").path("responseCode");
            return code.isInt() ? Optional.of(code.intValue()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    };
}
```

Repeat Steps 5 and 6. Expected: `responseCode` is extracted from the nested path and logged/recorded correctly. The default `JacksonResponseCodeExtractor` is not created (confirmed by the absence of a second `ResponseCodeExtractor` bean in the context).

### 8c — Disable the Starter

Add to `application.yml`:

```yaml
service-call-logging:
  enabled: false
```

Restart and repeat Steps 4–6.

Expected:
- No `X-Source-Service` or `X-Destination-Service` headers on outgoing requests
- No `responseCode` log entries
- No `http_outbound_calls_total` metric in `/actuator/prometheus`

---

## Known Limitations

Refer to `contracts/configuration.md` for the full list. Key limitations to validate explicitly:

1. **Non-Spring-builder HTTP clients**: If your service uses `new RestTemplate()` directly (not built via `RestTemplateBuilder`), the interceptor is not applied. Validate by checking whether headers appear on calls from that instance — if they do not, inject `RestTemplateBuilder` and use `.build()` instead.

2. **Reactive distributed trace propagation**: When using WebClient, MDC context (e.g., trace IDs) does not propagate automatically across reactive boundaries. If your logs show missing trace IDs on reactive call log entries, configure Micrometer Tracing with `ContextPropagation` support.

3. **Responses larger than `max-body-bytes`** (default 1 MB): These produce `responseCode=absent`. If you see unexpected `absent` entries for large responses, check the response `Content-Length` or increase `service-call-logging.max-body-bytes`. The business caller still receives the full response body.
