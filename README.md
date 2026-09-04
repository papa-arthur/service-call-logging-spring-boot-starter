# Service Call Logging Spring Boot Starter

Auto-configurable Spring Boot 3 starter that instruments **every outbound HTTP call** your
service makes. It stamps each request with source/destination correlation headers, reads the
business outcome code and message from the response envelope — using field names you configure,
if the defaults don't match your downstream API — logs them, and records Prometheus-ready
counters for Grafana dashboards and alerts.

It does all of that **without any code change in your service**, and — by design and by
enforced test gate — **without ever changing the outcome of the calls it observes**.

```xml
<dependency>
    <groupId>com.telecelghana.play.app.common</groupId>
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
- [Response envelopes: configuring field names](#response-envelopes-configuring-field-names)
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
INFO  c.t.p.a.c.s.logging.CallLogger - outbound-request source=my-service destination=payments-service:8080 \
      method=POST destinationUri=/accounts/{id}/transfers inboundUri=/api/v1/payments \
      operation=SendMoney
INFO  c.t.p.a.c.s.logging.CallLogger - outbound-req-response source=my-service destination=payments-service:8080 \
      method=POST destinationUri=/accounts/{id}/transfers inboundUri=/api/v1/payments \
      operation=SendMoney httpStatus=200 httpStatusGroup=2xx responseCode=0 responseMessage=OK
```

**Two entries per call, not one.** The first is written *before* the request is dispatched, so a
call that hangs until your client times out still leaves a record that it was attempted. The
second is written when the response arrives — or when the call fails, in which case it carries
`responseCode=absent`.

At `/actuator/prometheus` (when Actuator is present):

```
# TYPE http_outbound_calls_total counter
http_outbound_calls_total{destination="payments-service:8080",outcome="success",\
  http_status_group="2xx",destination_uri="/accounts/{id}/transfers",\
  inbound_uri="/api/v1/payments",operation="SendMoney"} 1432.0

# TYPE http_outbound_calls_latency_seconds histogram
http_outbound_calls_latency_seconds_bucket{destination="payments-service:8080",\
  outcome="success",http_status_group="2xx",destination_uri="/accounts/{id}/transfers",\
  inbound_uri="/api/v1/payments",operation="SendMoney",le="0.25"} 1301.0
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

Every key is optional. All ten are listed here with their type, default and effect.

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
| `service-call-logging.envelopes` | `List` | empty | Ordered list of response-envelope field-name combinations. Each entry takes `code-field` (default `responseCode`), `message-field` (default `message`) and `successful-value` (default `0`). See [Response envelopes](#response-envelopes-configuring-field-names). |

### Metrics

| Key | Type | Default | Effect |
|---|---|---|---|
| `service-call-logging.metrics.prefix` | `String` | `http.outbound.calls` | Micrometer counter name prefix; the counter is `<prefix>.total`. |
| `service-call-logging.metrics.destination-tag-name` | `String` | `destination` | Tag key for the destination service name. |
| `service-call-logging.metrics.outcome-tag-name` | `String` | `outcome` | Tag key for the call outcome. |
| `service-call-logging.metrics.status-group-tag-name` | `String` | `http_status_group` | Tag key for the HTTP status group. |
| `service-call-logging.metrics.destination-uri-tag-name` | `String` | `destination_uri` | Tag key for the destination URI **path**. |
| `service-call-logging.metrics.inbound-uri-tag-name` | `String` | `inbound_uri` | Tag key for the path of the inbound request being handled. |
| `service-call-logging.metrics.operation-tag-name` | `String` | `operation` | Tag key for the caller-supplied business operation. |
| `service-call-logging.metrics.latency-buckets` | `List<String>` | *(empty)* | Latency bucket boundaries for the `<prefix>.latency` timer, e.g. `[50ms, 200ms, 1s]`. Empty means **delegate to Micrometer's own default distribution** — see below. |

#### `latency-buckets`: the default is delegated, not ours

When `latency-buckets` is unset, this starter defines **no** boundaries of its own. It calls
Micrometer's `publishPercentileHistogram()`, so the effective buckets belong to the **Micrometer
version on your classpath**, not to this starter.

Measured against the version this release was built and verified with — **Micrometer 1.13.15**,
via a Prometheus registry — that produces **68 boundaries** spanning **1 ms to 30 s**:

```
1ms, 1.049ms, 1.398ms, 1.748ms, 2.097ms, 2.447ms, 2.796ms, 3.146ms, 3.495ms, 3.845ms, 4.194ms,
5.592ms, 6.991ms, 8.389ms, 9.787ms, 11.185ms, 12.583ms, 13.981ms, 15.379ms, 16.777ms, 22.370ms,
27.962ms, 33.554ms, 39.147ms, 44.739ms, 50.332ms, 55.924ms, 61.516ms, 67.109ms, 89.478ms,
111.848ms, 134.218ms, 156.587ms, 178.957ms, 201.327ms, 223.696ms, 246.066ms, 268.435ms,
357.914ms, 447.392ms, 536.871ms, 626.349ms, 715.828ms, 805.306ms, 894.785ms, 984.263ms,
1.074s, 1.432s, 1.790s, 2.147s, 2.505s, 2.863s, 3.221s, 3.579s, 3.937s, 4.295s, 5.727s, 7.158s,
8.590s, 10.022s, 11.453s, 12.885s, 14.317s, 15.748s, 17.180s, 22.906s, 28.633s, 30s
```

**Upgrading Micrometer can change these boundaries with no change to this starter and no change to
its version number.** If you need boundaries that are stable across upgrades, set
`latency-buckets` explicitly — that is the only way to pin them.

> **⚠ Cardinality warning — read before leaving `latency-buckets` unset.**
> Those 68 boundaries are multiplied by **every combination of the timer's six tags**. A service
> with 20 destination URI templates, 15 inbound templates, 5 operations, 3 outcomes and 6 status
> groups reaches `20 × 15 × 5 × 3 × 6 = 27,000` tag combinations, and `27,000 × 68 ≈ 1.8 million`
> time series. Real traffic never fills the full cross product, but the order of magnitude is the
> point: it is enough to destabilise a metric store, and it lands on a service that configured
> nothing.
>
> **A service with many endpoints, many downstream endpoints, or many operations should set a
> short explicit `latency-buckets` list** — five to ten boundaries is ample for percentile and
> SLO views.

### Everything, with defaults shown

```yaml
service-call-logging:
  enabled: true
  source-header-name: X-Source-Service
  destination-header-name: X-Destination-Service
  service-name-hint-header: service_name
  max-body-bytes: 1048576
  envelopes: []          # empty = use responseCode / message / 0 for every call
  metrics:
    prefix: http.outbound.calls
    destination-tag-name: destination
    outcome-tag-name: outcome
    status-group-tag-name: http_status_group
    destination-uri-tag-name: destination_uri
    inbound-uri-tag-name: inbound_uri
    operation-tag-name: operation
    latency-buckets: []    # empty = delegate to Micrometer's default distribution (68 buckets)
```

Full contract: [`contracts/configuration.md`](specs/001-outbound-http-observability/contracts/configuration.md).

---

## Response envelopes: configuring field names

The starter reads two things from each JSON response body: a numeric **outcome code** and a
human-readable **message**. By default it looks for `responseCode` and `message`, and treats `0`
as success.

Different APIs name these differently. Configure the pairs you actually call:

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
      message-field: message
    - code-field: responseCode
      message-field: responseDescription
      successful-value: 1
```

**How a combination is chosen — per call, by the response body, not by destination.** For each
response, the starter walks your list in order and uses the **first entry whose `code-field` is
actually present** in the body, falling back to the built-in `responseCode`/`message`/`0` when
none match. Nothing is bound to a hostname, so one service can call any number of APIs with
different conventions and each call is interpreted correctly on its own.

| Response body (given the config above) | `responseCode` | `responseMessage` | Outcome |
|---|---|---|---|
| `{"statusCode":0,"message":"OK"}` | `0` | `OK` | success |
| `{"statusCode":7,"message":"Declined"}` | `7` | `Declined` | failure |
| `{"responseCode":1,"responseDescription":"OK"}` | `1` | `OK` | success — entry 2's successful value is `1` |
| `{"responseCode":0,"responseDescription":"Nope"}` | `0` | `Nope` | failure — `0 != 1` for entry 2 |
| `{"errorCode":500}` | `absent` | `absent` | absent |

Three rules worth knowing:

1. **Only the successful value is privileged.** Every other numeric value the code field can
   hold is unsuccessful — including one you've never seen before. Nothing is ever silently
   dropped as "unrecognised".
2. **Order matters.** If a body could satisfy two entries, the earlier one wins. Put the more
   specific entry first.
3. **Each field falls back on its own.** Configure only `code-field` and the message is still
   read from `message`.

Full contracts:
[`configuration.md`](specs/002-configurable-envelope-fields/contracts/configuration.md) ·
[`envelope-matching.md`](specs/002-configurable-envelope-fields/contracts/envelope-matching.md)

---

## What gets logged

The set of logged fields is **fixed and exhaustive**. These ten, and nothing else:

| Field | Meaning | On which entry |
|---|---|---|
| `source` | Your `spring.application.name`, or `unknown` | both |
| `destination` | Resolved destination service name | both |
| `method` | HTTP method of the outgoing request | both |
| `destinationUri` | **Path** of the call's destination — the URI template when your client expanded one, else the raw path, else `unknown` | both |
| `inboundUri` | **Path** of the inbound request you were handling when the call was made, else `unknown` | both |
| `operation` | The `X-Operation` header value you supplied, else `undefined` | both |
| `httpStatus` | Received HTTP status code, or `none` on a network error | response only |
| `httpStatusGroup` | `2xx` / `4xx` / `5xx` / `network-error` (also `1xx` / `3xx`) | response only |
| `responseCode` | Parsed business code, or `absent` | response only |
| `responseMessage` | Parsed business message, logged verbatim with no length limit, or `absent` | response only |

The send-time entry carries no status, response code or message because none of them exist yet.

**Neither URI field ever contains a scheme, host, port, embedded credential or query string.** That
is structural rather than a redaction step: the recorded value is the URI's *path* component, and a
credential lives in the authority while a token lives in the query, so neither is representable in
what gets recorded. Verified by an adversarial test, not by inspection.

### The `X-Operation` header

Set it per call to name the business operation, and every log entry and metric series for that call
carries it:

```java
HttpHeaders headers = new HttpHeaders();
headers.set("X-Operation", "SendMoney");
restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Response.class);
```

That is all the integration required — the starter only ever *reads* this header. It never adds,
removes or rewrites it, so whatever you set arrives at the destination byte-identical, **including
a value the starter rejects for telemetry**.

The header name is fixed and deliberately **not** configurable: it is a per-call input from the
caller, not a per-service identity like the correlation headers, so there is nothing a
service-wide property could usefully say about it.

Two bounds apply, and they protect different things:

| Bound | Rule | Why |
|---|---|---|
| Shape | ≤ 64 characters from `[A-Za-z0-9._-]` | Excludes whitespace and separators that would break log-line parsing, and makes a value carrying an account number unlikely to pass by accident |
| Distinct values | At most **100** distinct values per process | The shape bound cannot bound the *count*: `SendMoney-0001`, `SendMoney-0002` … all satisfy it while being unbounded in number, and each new value is a new metric series |

Anything failing either bound is reported as `undefined`. Occupancy is first-come-first-served with
no eviction: a high-volume junk caller cannot displace an operation already admitted, because a
dimension whose membership shifted under load would change your dashboard's meaning mid-incident.
Neither bound is configurable — the dimension is shared infrastructure, and one service raising its
own cap would spend cardinality everyone pays for.

The starter **never** logs credentials, tokens, `Authorization` or `Cookie` header values, PII,
or request/response bodies. This is enforced statically: a build-time check fails if any
compiled class so much as carries a credential header name as a string constant, and an
architecture rule prevents the logging package from depending on header types at all.

Instrumentation failures log at `WARN` with only the exception type and message — never a
stack trace carrying request state.

---

## Metrics and Grafana

Two meters, six tags. Requires Actuator on your classpath; without it, no metrics are recorded
and nothing fails.

| | Counter | Latency timer |
|---|---|---|
| **Micrometer name** | `http.outbound.calls.total` | `http.outbound.calls.latency` |
| **Prometheus name** | `http_outbound_calls_total` | `http_outbound_calls_latency_seconds` |
| **Type** | Counter (monotonic) | Timer with a bucketed distribution |
| **Recorded** | once per completed or failed call | elapsed time per call, **including calls that fail before a response** |

Both meters carry an **identical** tag set — they are tagged from one shared assembly, so a
dimension cannot reach one meter and miss the other:

| Tag | Values | Bounded by |
|---|---|---|
| `destination` | resolved destination service name | your downstream inventory |
| `outcome` | `success` (responseCode 0) · `failure` (non-zero) · `absent` (unparseable) | fixed at 3 |
| `http_status_group` | `2xx` · `4xx` · `5xx` · `network-error` (also `1xx` · `3xx`) | fixed at 6 |
| `destination_uri` | destination **path** — URI template when your client expanded one; `unresolved` when it did not; `unknown` when no URI could be determined | your route inventory + 2 literals |
| `inbound_uri` | **path** of the inbound request you were handling, else `unknown` | your own route table + 1 literal |
| `operation` | your `X-Operation` value, else `undefined` | **≤ 101** (100 admitted + `undefined`) |

**`destination_uri` is `unresolved`, not the raw path, when there is no template.** The log entry
keeps the raw path because detail is free in a log line; a metric tag does not get it, because a
path carrying an account number is unique per request and would create one series per request.

**All three of `outcome`'s values are distinct and none is collapsed.** A call whose response code
could not be determined is `absent` — neither a success nor a failure — so "how much of this
operation succeeded?" is never silently answered with undetermined calls counted as failures.

Every tag is present on every call. No tag is ever omitted, so a series never appears and
disappears for the same meter.

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

**Failure share of one business operation** — the question the `operation` tag exists for

```promql
sum(rate(http_outbound_calls_total{operation="SendMoney",outcome="failure"}[5m]))
  / sum(rate(http_outbound_calls_total{operation="SendMoney"}[5m]))
```

**Which of *your* endpoints is generating failing downstream calls**

```promql
sum by (inbound_uri, destination_uri) (
  rate(http_outbound_calls_total{outcome="failure"}[$__rate_interval]))
```

**p99 latency, by the endpoint that triggered the call**

```promql
histogram_quantile(0.99,
  sum by (le, inbound_uri) (rate(http_outbound_calls_latency_seconds_bucket[5m])))
```

**Share of SendMoney calls completing within 500 ms** — an SLO view

```promql
sum(rate(http_outbound_calls_latency_seconds_bucket{operation="SendMoney",le="0.5"}[5m]))
  / sum(rate(http_outbound_calls_latency_seconds_count{operation="SendMoney"}[5m]))
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

**Try configuration first.** If your envelope differs only in its *field names* or its success
value, you don't need code at all — see
[Response envelopes](#response-envelopes-configuring-field-names). Reach for a custom bean when
the code isn't a top-level field, or isn't JSON.

The default reads a top-level integer code from a JSON body, using the field names you
configured. If that isn't enough, declare your own bean — the default is
`@ConditionalOnMissingBean`, so yours wins automatically and the default is never created.

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

`Optional.of(<the successful value>)` → `success`; any other value → `failure`;
`Optional.empty()` → `absent`. The raw integer is logged whatever it is, so you can correlate log
lines with specific business error codes.

**Your bean still gets a message.** Message extraction runs independently of who supplies the
code, so adding an `envelopes` entry gives an existing custom extractor a `responseMessage` with
no change to your bean at all (subject to known limitation 7 below).

Full contract: [`contracts/response-code-extractor-spi.md`](specs/001-outbound-http-observability/contracts/response-code-extractor-spi.md).

---

## Overriding anything else

Every bean the starter contributes is `@ConditionalOnMissingBean`. Declare your own bean of the
same type and yours is used instead:

| Bean | Why you might replace it |
|---|---|
| `ResponseCodeExtractor` | Response code that configuration alone can't express (e.g. nested, or non-JSON) |
| `EnvelopeFieldExtractor` | Full control of envelope matching and message extraction |
| `DestinationNameResolver` | Normalise destination names to control metric cardinality |
| `CallLogger` | Change the log format, or emit structured JSON — **override `logRequest` as well as `log`**, or you get no send-time entry (see below) |
| `OutboundCallMetrics` | Custom tagging |
| `OutboundCallInterceptor` | Full control of the blocking path |
| `OutboundCallExchangeFilter` | Full control of the reactive path |
| `serviceCallLoggingRestTemplateCustomizer` (by name) | Change how the interceptor is attached |
| `serviceCallLoggingWebClientCustomizer` (by name) | Change how the filter is attached |

### Subclassing `CallLogger`: override both methods

`CallLogger` now emits **two** entries per call, from two methods:

| Method | Entry |
|---|---|
| `logRequest(OutboundCallRecord)` | `outbound-request`, before dispatch |
| `log(OutboundCallRecord)` | `outbound-req-response`, on completion |
| `logWarn(...)` | `outbound-call-instrumentation-error`, only when instrumentation itself fails |

If you subclass and override only `log`, your format applies to the response entry and the
send-time entry silently keeps the default format. Nothing fails and nothing warns — you simply
get a log surface you did not intend. Override both.

### Behaviour changes from the pre-003 code

No migration is owed: 1.0.0 is unpublished and there are no consumers. These are documented
because anyone running the starter from source will see them, and the first adopter inherits them.

| If you… | Then… |
|---|---|
| Grep or parse for `outbound-call` | The per-call entry is now `outbound-req-response`. Note `outbound-call-instrumentation-error` still begins with those characters, so a naive prefix match now catches **only** the warning |
| Count log entries per call | It doubled. Budget for two INFO entries per outbound call — a material change to log volume and ingest cost at high call rates |
| Parse log fields positionally | Three fields were added. Parse by name |
| Query the counter with an exact tag set | It gained `destination_uri`, `inbound_uri` and `operation`, so series identity changed. Queries that **aggregate away** the tags they don't name keep working unchanged; queries asserting a complete tag set need revising |
| Subclass `CallLogger` | See above — override `logRequest` too |

### Base package renamed: `com.bookit` → `com.telecelghana.play.app.common`

The starter's base package and Maven `groupId` moved from `com.bookit` to
`com.telecelghana.play.app.common`. Nothing the starter *does* changed — the same fields are
logged, the same meters and tags are recorded, the same properties and header names apply, and the
log message text is byte-identical. What changed is every name derived from the package.

The version stays `1.0.0`, so a dependency diff gives you no signal that anything moved. This table
is the signal.

| If you… | Then… |
|---|---|
| Declare the dependency | `groupId` is now `com.telecelghana.play.app.common`. `artifactId` and `version` are unchanged |
| Import any starter type | Replace the `com.bookit.servicecalllogging.` prefix with `com.telecelghana.play.app.common.servicecalllogging.`. Class names themselves are unchanged, so it is a prefix swap and nothing more |
| Set log levels or filters on `com.bookit.*` | **Fails silently.** Loggers are per-class, so the categories moved to `com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger` and `...servicecalllogging.resolver.DestinationNameResolver`. A stale `logback-spring.xml` logger, `logging.level.*` entry, log-shipping route or alert rule stops matching without any error — the telemetry just disappears from your pipeline. Key on the parent prefix `com.telecelghana.play.app.common.servicecalllogging` so future moves inside the starter don't repeat this |
| Exclude the auto-configuration **by string** — `spring.autoconfigure.exclude=…` or `excludeName=…` | **Fails silently, and the starter turns back on.** Spring only reports an invalid exclusion when the named class is on the classpath; after the rename the old name isn't, so the entry is dropped without warning and instrumentation re-attaches. Update the name to `com.telecelghana.play.app.common.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration`, or use `service-call-logging.enabled=false`, which is unaffected by the rename |
| Exclude it by class literal — `exclude = ServiceCallLoggingAutoConfiguration.class` | Safe. This breaks at compile time, so you cannot miss it |
| Read `/actuator/configprops` or `/actuator/beans` programmatically | The configuration-properties bean name embeds the type's FQCN, so the key is now `service-call-logging-com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties`. The starter's own `@Bean`-named beans (`callLogger`, `destinationNameResolver`, `outboundCallMetrics`, and the two customizers) are unchanged |
| Query metrics, read config keys, or match header names | Unaffected. Meter names, tag keys and values, every `service-call-logging.*` property key and all header names are string literals and did not move |

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
starter's own work — name resolution, header stamping, body buffering, JSON extraction, logging,
the counter increment and the latency timer:

| Scenario (52-byte JSON envelope) | Overhead per call |
|---|---|
| No `envelopes` configured | **~7.9–8.7 µs** |
| Three combinations configured, matching one last | **~4.8–5.4 µs** |

Re-measured across three runs after this feature landed (Micrometer 1.13.15). The **worst-case
budget to plan against is unchanged: under 10 µs per call** for envelopes up to a few KB — but note
the headroom is now thinner than it was, roughly 8 µs of a 10 µs budget rather than 3–4 µs. Adding
much more per-call work would need the budget revisited rather than quietly exceeded.

The figures do not include logging I/O: the benchmark silences both log entries so it measures the
starter's own work — name resolution, header stamping, URI resolution, operation resolution, body
buffering, JSON extraction, the counter and the timer — rather than the throughput of whatever
logging backend you have configured. **Your real per-call cost will be higher than this**, by
roughly the cost of two INFO lines through your appender. That is worth measuring on your own
stack if you make millions of outbound calls a day.

Ignore the gap between the two rows: neither is an average of many runs, and the ordering has
inverted between measurements, so it does not reflect a real effect of configuring envelopes.

For context, that is roughly **three to four orders of magnitude below** a typical network round
trip (1–100 ms). Cost scales with body size up to `max-body-bytes` and stops there: past the cap
nothing is parsed at all.

Worst-case budget to plan against: **under 10 µs per call** for envelopes up to a few KB.
If you need to re-measure on your own hardware:

```bash
mvn test-compile
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
java -cp "target/classes:target/test-classes:$(cat cp.txt)" \
  com.telecelghana.play.app.common.servicecalllogging.OverheadBenchmark
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

6. **Envelope matching is order-sensitive.** If a response body satisfies more than one
   configured `code-field`, the earliest entry in your list wins, deterministically. Order your
   combinations from most specific to least — the starter cannot detect an ambiguous
   configuration for you.

7. **A custom `ResponseCodeExtractor` whose code isn't a same-named JSON field doesn't get a
   configurable successful value.** If you decode a non-JSON or binary protocol, no configured
   combination can ever match the body, so classification falls back to the built-in successful
   value `0` — even if you configured something else. Your message will also be `absent`. Code
   extraction itself is unaffected: your bean is still used, exactly as before.

8. **The message is logged verbatim, with no length limit and no content filtering.** A
   downstream API that returns a very long message, or one echoing data you'd rather not have in
   your logs, will have it appear in full. You choose which field to read; that choice is yours
   to make deliberately.

9. **`inboundUri` is `unknown` for any call not made on the thread handling the inbound request.**
   The starter reads the matched endpoint pattern from the current request context, which is
   thread-bound. So a call made from a scheduled task, a startup hook, an `@Async` method, a
   `CompletableFuture` continuation, or anywhere on the reactive path reports `unknown` rather
   than the endpoint that triggered it. This is deliberate, and it is the safe direction to fail:
   a pooled thread could otherwise report a *previous* request's endpoint, and a call attributed
   to the wrong endpoint sends you to innocent code during an incident — worse than no
   attribution at all. Carrying the context across a thread hand-off is your service's
   responsibility, consistent with limitation 2.

10. **The two entries for one call are paired using *your* log correlation, not anything the
    starter adds.** Each call now emits `outbound-request` and `outbound-req-response`, and the
    starter deliberately adds **no** correlation field of its own — the loggable field set stays
    as small as it is. Pairing therefore relies on the trace and span identifiers your own logging
    setup stamps on every line. If you have not configured log correlation, or you are on the
    reactive path (where propagation is your responsibility — see limitation 2), **you cannot
    reliably tell which request entry belongs to which response entry while calls run
    concurrently.** Both entries are still emitted in full; only the pairing is unavailable. This
    is the accepted cost of adding no field, not a defect awaiting a fix.

11. **`destinationUri` is the URI template only when the client expanded one.** Call
    `getForObject("/accounts/{id}", ..., 42)` and you get `/accounts/{id}`. Pass a pre-built
    `URI` and there is no template to report, so the log entry shows the raw path
    (`/accounts/42`) and the **metric tag** shows `unresolved` instead. That asymmetry is
    intentional: a raw path carrying an account number is unique per request, and putting it in a
    metric tag would create one time series per request. Detail you want in a log line is exactly
    what you do not want in a tag.

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
  The business message is part of that set by deliberate amendment to the project
  constitution (v1.1.0) — nothing else was admitted with it.
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
