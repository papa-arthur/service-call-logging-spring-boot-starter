# Feature Specification: Outbound HTTP Observability Starter

**Feature Branch**: `001-outbound-http-observability`

**Created**: 2026-08-27

**Status**: Draft

**Input**: User description: "A reusable, auto-configurable Spring Boot starter that instruments
every outbound HTTP call a service makes: it stamps the request with source- and destination-
service correlation headers, reads the standard response envelope's responseCode, and logs that
code alongside the headers — without changing how consuming services write their code and without
breaking the calls it observes. Create metrics that can be used to create dashboards and alerts
on Grafana on what successful and failed calls based on their responseCodes. Metrics should be
able to tell number of service calls that failed or succeeded to a given destination service.
The rate at which they are failing or succeeding."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Zero-Touch Instrumentation of Outbound Calls (Priority: P1)

A platform engineer adds the starter as a dependency to a service. From that point forward, every
outbound HTTP call the service makes is automatically stamped with the source-service name and
destination-service name as request headers, and when the response arrives, the `responseCode`
from the response envelope is parsed and logged alongside those header values. The service's
existing code is unchanged, and every call still returns the same response the service expected.

**Why this priority**: This is the entire value proposition of the starter. Every other feature
builds on top of transparent instrumentation. Without this working correctly, nothing else
matters.

**Independent Test**: Can be fully tested by adding the starter to a minimal service that makes
a single outbound call, then asserting that: (a) the outgoing request carries the two correlation
headers; (b) the log output contains the header values and the parsed `responseCode`; (c) the
response received by the calling code is byte-for-byte identical to the uninstrumented response.

**Acceptance Scenarios**:

1. **Given** a service with the starter on its classpath and a standard outbound HTTP call
   configured, **When** the service starts and makes that call, **Then** the outgoing request
   contains a source-service header with the calling service's name and a destination-service
   header with the target service's name, without any code change to the consuming service.

2. **Given** a service making an outbound call that returns a JSON response envelope containing
   `responseCode: 0`, **When** the response is received, **Then** a log entry is emitted
   containing the source header value, destination header value, and `responseCode=0`
   (indicating success), and the full response body remains readable by the calling service.

3. **Given** a service making an outbound call that returns a JSON response envelope containing
   `responseCode: 1`, **When** the response is received, **Then** a log entry is emitted
   with `responseCode=1` (indicating failure), and the response is returned unmodified to the
   caller.

4. **Given** an exception is thrown inside the instrumentation path during header stamping or
   response parsing, **When** this exception occurs, **Then** the business call completes
   normally with its original outcome, and the log entry records reduced telemetry (e.g.,
   missing fields) rather than propagating the error.

---

### User Story 2 — Outbound Call Outcome Metrics for Grafana (Priority: P2)

A platform engineer opens Grafana and can see, for each destination service, the total number
of successful outbound calls (responseCode=0), the total number of failed outbound calls
(responseCode=1), the number of calls where no responseCode was present, and the rate at which
successes and failures are occurring over any chosen time window. Alerts can be configured on
failure counts and failure rates.

**Why this priority**: Without metrics, the logging alone has limited operational value. Metrics
enable dashboards, alerting, and SLA tracking — the primary operational use case described.

**Independent Test**: Can be fully tested by: running a service with the starter; making a mix
of calls returning responseCode=0, responseCode=1, and no responseCode; then querying the
metrics endpoint and verifying that counters for each outcome and each destination are present
and correctly incremented.

**Acceptance Scenarios**:

1. **Given** the starter is active and the consuming service has made outbound calls to two
   different destination services, **When** the metrics endpoint is queried, **Then** separate
   metric entries exist for each destination service, each labelled with the outcome
   (success / failure / absent), and the counts match the actual number of calls made.

2. **Given** a series of outbound calls over time to a destination service, **When** the metrics
   are scraped at regular intervals by a compatible monitoring system, **Then** the per-interval
   call counts are sufficient for that system to compute the success rate and failure rate over
   any user-chosen time window without additional data transformation.

3. **Given** calls that return responseCode=0 and calls that return responseCode=1 to the same
   destination service, **When** a Grafana dashboard is created using the scraped metrics,
   **Then** the dashboard can display: total call count, success count, failure count, absent
   count, and success/failure rates, all filterable by destination service.

4. **Given** the metrics endpoint is queried for a destination service that has received only
   failed calls, **When** the response is inspected, **Then** the success counter for that
   destination shows zero and the failure counter shows the correct non-zero count.

---

### User Story 3 — Graceful Handling of Non-Standard Responses (Priority: P3)

A service operator's service calls a third-party endpoint that does not use the standard response
envelope. The starter cannot parse `responseCode` from these responses, but the call still
completes normally, the response body is fully returned to the calling code, and a log entry
is emitted recording `responseCode=absent` — without errors or exceptions surfacing to the
business logic.

**Why this priority**: Real environments always include non-conforming endpoints. Safe degradation
is required for the starter to be trustworthy across all teams' services.

**Independent Test**: Can be fully tested by asserting all four degradation cases independently:
empty body, non-JSON body, JSON body without `responseCode`, and an instrumentation-path
exception — each producing `responseCode=absent` in the log and returning the original response
intact.

**Acceptance Scenarios**:

1. **Given** an outbound call that returns an empty response body, **When** the instrumentation
   path attempts to parse `responseCode`, **Then** the log records `responseCode=absent`, no
   exception is thrown into the call path, and the empty response is returned to the caller.

2. **Given** an outbound call that returns a non-JSON body (e.g., plain text or HTML),
   **When** the instrumentation path attempts to parse `responseCode`, **Then** the log records
   `responseCode=absent`, no exception surfaces, and the original non-JSON body is returned
   intact to the caller.

3. **Given** an outbound call that returns valid JSON without a `responseCode` field, **When**
   the instrumentation attempts to read that field, **Then** the log records `responseCode=absent`
   and the full JSON body is returned unmodified to the caller.

4. **Given** a very large streaming response body, **When** the instrumentation path processes
   it, **Then** the body is NOT fully buffered into memory; the streaming semantics are
   preserved; the response is returned to the caller; and `responseCode=absent` is logged (since
   full buffering is not permitted).

---

### User Story 4 — Starter Configuration & Override (Priority: P4)

A platform engineer needs to customise the starter's behaviour: change the default correlation
header names, override how `responseCode` is extracted for a service that uses a non-standard
envelope, or disable the starter entirely for a canary deployment.

**Why this priority**: Overridability is what makes the starter safe to adopt at scale — teams
can opt out or customise without forking.

**Independent Test**: Can be fully tested by: asserting custom header names appear on outgoing
requests after configuration; confirming a consumer-provided extraction bean takes effect;
confirming no instrumentation occurs when the starter is disabled.

**Acceptance Scenarios**:

1. **Given** a consuming service sets custom names for the source and destination correlation
   headers in its configuration, **When** it makes an outbound call, **Then** those custom
   header names (not the defaults) appear on the outgoing request.

2. **Given** a consuming service provides its own response-code extraction implementation,
   **When** the starter initialises, **Then** the consuming service's implementation is used
   instead of the default, and the default extractor bean is not instantiated.

3. **Given** a consuming service sets the starter's enabled property to `false`, **When** the
   service starts and makes outbound calls, **Then** no correlation headers are added, no
   response parsing occurs, no log entries are emitted by the starter, and no starter-specific
   metrics are registered.

---

### Edge Cases

- What happens when the target URL host is an IP address and no `service_name` header is
  present — is `ip-address:port` an acceptable metric label, or should it be normalised?
- What happens when the consuming service uses multiple concurrent HTTP clients (both blocking
  and reactive) simultaneously?
- What happens when the same destination service is called with different URL paths — are calls
  aggregated by service name or disaggregated by path?
- What happens when the consuming service has not configured a Spring application name — the
  source header value MUST be `"unknown"` and a WARNING MUST be emitted at startup; the starter
  MUST still initialise and instrument calls normally.
- What happens when the metrics scrape endpoint is unavailable or the monitoring backend is
  unreachable?
- What HTTP status code group label is applied when the call never receives an HTTP response
  (connection refused, read timeout, DNS failure) — is this classified as `network-error`
  regardless of whether an HTTP status code was observed?
- What happens if the response body is read by instrumentation and then read again by the
  calling code — is the second read still valid?

## Requirements *(mandatory)*

### Functional Requirements

#### Correlation Header Instrumentation

- **FR-001**: The starter MUST automatically add a source-service identifier header to every
  outbound HTTP request made through the consuming service's Spring-managed HTTP client(s),
  where the header value is the consuming service's application name. When the application name
  is not configured, the starter MUST use the literal value `"unknown"` as the header value and
  MUST emit a WARNING-level log entry at startup stating that the source service name is
  unconfigured; starter initialisation MUST NOT fail in this case.
- **FR-002**: The starter MUST automatically add a destination-service identifier header to
  every outbound HTTP request. The destination service name MUST be resolved using the following
  priority order:
  1. If the outgoing request already carries a `service_name` header (name configurable),
     its value MUST be used as-is — no URL derivation is performed.
  2. Otherwise, the destination name MUST be derived from the target URL as `host:port`
     (e.g., `payment-service.internal:8080`). When the host portion is an IP address, the
     value MUST be `ip-address:port` (e.g., `192.168.1.100:8080`).
- **FR-003**: The name of the source-service header MUST be configurable via a documented
  configuration property, with a safe documented default.
- **FR-004**: The name of the destination-service header MUST be configurable via a documented
  configuration property, with a safe documented default.
- **FR-005**: The starter MUST NOT require the consuming service to add annotations, modify
  bean definitions, or change existing HTTP client construction code to receive header stamping.

#### Response Envelope Parsing & Logging

- **FR-006**: After each outbound HTTP call completes, the starter MUST attempt to read the
  `responseCode` integer field from the top-level JSON object of the response body.
- **FR-007**: A parsed `responseCode` value of `0` MUST be classified as a successful outcome;
  any non-zero integer value MUST be classified as an unsuccessful outcome. (The value `1` is
  the canonical failure code; other non-zero codes represent distinct business errors and are
  also classified as unsuccessful.)
- **FR-008**: The starter MUST emit a log entry for each instrumented call containing: the
  source-service header value, the destination-service header value, and the parsed `responseCode`
  (or `absent` if unparseable).
- **FR-009**: The log entry MUST NOT contain credentials, tokens, `Authorization` header values,
  `Cookie` header values, PII, full request bodies, or full response bodies.
- **FR-010**: When the response body is absent, empty, non-JSON, or does not contain a
  `responseCode` field, the starter MUST log `responseCode=absent` and MUST NOT throw an
  exception, log at error level, or prevent the response from reaching the calling code.
- **FR-011**: The response body MUST remain fully and identically readable by the calling code
  after the starter has read and parsed it.

#### Non-Intrusion & Safety

- **FR-012**: Any exception or error thrown inside the instrumentation path MUST NOT propagate
  into the business call result, alter the returned response, or add latency beyond a documented
  bounded overhead.
- **FR-013**: The starter MUST NOT buffer response bodies larger than a configurable maximum size
  whose documented default is **1 MB**; when a response body exceeds this limit, the starter MUST
  log `responseCode=absent` without buffering or reading the body, preserving streaming semantics.
  The maximum size MUST be configurable via a documented configuration property.

#### Call Outcome Metrics

- **FR-014**: The starter MUST maintain a counter metric that tracks the cumulative number of
  outbound calls, labelled by destination-service name and by outcome (success, failure, absent).
- **FR-015**: The metrics MUST be exported in a format natively consumable by Grafana via a
  Prometheus-compatible scrape endpoint, requiring no custom data transformation by the
  consuming team.
- **FR-016**: The metric labels MUST include at minimum: destination service name, call outcome
  (success / failure / absent), and HTTP response status code group (2xx / 4xx / 5xx /
  network-error). The `network-error` group applies when no HTTP response is received (e.g.,
  connection refused, read timeout, DNS failure).
- **FR-017**: The counter metrics MUST be sufficient for a monitoring system to compute, via
  its standard rate functions, the per-destination success rate and failure rate over any
  user-chosen time window.
- **FR-018**: The metric name prefix MUST be configurable via a documented configuration
  property. The documented default prefix MUST be `http.outbound.calls`. This prefix is chosen
  to be distinct from Spring Boot Actuator's `http.client.requests` (timing) and
  `http.server.requests` (inbound) metrics, avoiding naming collisions.
- **FR-019**: Metric label/tag names MUST be configurable via documented configuration
  properties, with documented defaults.

#### Auto-Configuration & Overridability

- **FR-020**: The starter MUST auto-configure all instrumentation beans without any bean
  definition, annotation, or code change required in the consuming service.
- **FR-021**: Every bean contributed by the starter MUST be overridable: if a consuming service
  defines a bean of the same type, the consuming service's bean MUST take precedence and the
  starter's bean MUST NOT be instantiated.
- **FR-022**: The `responseCode` extraction behaviour MUST be replaceable via a documented,
  named extension point; a consuming service implementing that extension point MUST have its
  implementation used in place of the default.
- **FR-023**: The entire starter MUST be disable-able via a single documented boolean
  configuration property; when disabled, no instrumentation beans are registered and no headers,
  logs, or metrics are produced.
- **FR-024**: Blocking HTTP client instrumentation MUST activate only when a blocking HTTP
  client library (e.g., the standard synchronous web client) is present on the consuming
  service's classpath.
- **FR-025**: Reactive HTTP client instrumentation MUST activate only when a reactive HTTP
  client library is present on the consuming service's classpath.
- **FR-026**: Adding the starter MUST NOT alter a consuming service's startup success, introduce
  transitive dependencies the consuming service did not already have, or change its dependency
  graph beyond the starter artifact itself.

### Key Entities

- **Outbound Call Record**: Represents one intercepted outbound HTTP call — source service name,
  destination service name (resolved as `host:port` or from `service_name` header), HTTP method,
  HTTP response status code, HTTP status code group, parsed `responseCode` (integer or absent),
  call timestamp.
- **Response Envelope**: The JSON response body structure containing at minimum a top-level
  integer `responseCode` field (0 = success, 1 = unsuccessful); the starter's extraction
  behaviour is replaceable for non-standard envelopes.
- **Call Outcome Metric**: A labelled, ever-increasing counter tracking call outcomes
  (success / failure / absent) per destination service, additionally labelled by HTTP response
  status code group (2xx / 4xx / 5xx / network-error); designed for rate-of-change computation
  by the monitoring system. Default metric name prefix: `http.outbound.calls`.
- **Correlation Headers**: The pair of HTTP request headers — source-service name and
  destination-service name — stamped onto every outgoing request by the starter.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service team reports zero code changes required to their service to receive
  correlation header stamping, responseCode logging, and call outcome metrics after adding the
  starter.
- **SC-002**: A Grafana dashboard showing per-destination call counts and success/failure rates
  can be created and is fully populated within one scrape interval after the first instrumented
  call is made, with no additional configuration by the consuming team.
- **SC-003**: The dashboard accurately shows, per destination service: total call count,
  success count (responseCode=0), failure count (responseCode=1), absent count, success and
  failure rates over any user-selected time window, and call counts broken down by HTTP status
  code group (2xx / 4xx / 5xx / network-error).
- **SC-004**: Alerts can be configured in Grafana on failure count thresholds and failure rate
  thresholds per destination service, firing correctly in response to injected failures in a
  test environment.
- **SC-005**: 100% of instrumentation-path errors (parsing failures, body read errors,
  configuration errors) result in reduced telemetry only — not a failed, altered, or delayed
  business call.
- **SC-006**: All instrumented calls return responses that are byte-for-byte identical to
  responses from the same service without the starter present.
- **SC-007**: The per-call overhead introduced by the starter does not cause any measurable
  latency regression in a load test at the 99th percentile response time.

## Assumptions

- The consuming service uses Spring-managed HTTP client(s) constructed via standard framework
  builder mechanisms; HTTP clients constructed outside Spring's standard builders are not
  instrumented (known limitation — must be documented in the README).
- The source service name is read from the standard Spring application name configuration
  property. If that property is absent, the value `"unknown"` is used and a WARNING is emitted
  at startup; the starter continues to function normally.
- The destination service name for header values and metric labels is resolved in priority order:
  (1) the value of an inbound `service_name` request header (if present, header name configurable);
  (2) `host:port` derived from the target URL, where host is either a hostname or an IP address.
- The consuming service's infrastructure already exposes a Prometheus-compatible metrics scrape
  endpoint (e.g., via Spring Boot Actuator); the starter adds outbound-call metrics to the
  existing endpoint.
- Grafana is already configured to scrape the consuming service's metrics endpoint; this starter
  does not provision or configure Grafana.
- The shared response envelope always uses `responseCode` as the top-level field name; services
  using a different field name MUST provide a custom extraction implementation via the extension
  point.
- Distributed-trace and log-correlation propagation on the reactive path is the consuming
  service's responsibility (known limitation — must be documented in the README).
- Metrics include three labels: destination service name, call outcome (success/failure/absent),
  and HTTP response status code group (2xx / 4xx / 5xx / network-error).
- The starter targets Spring Boot 3.x and Java 17+.
- The default metric name prefix `http.outbound.calls` is intentionally distinct from Spring
  Boot Actuator's `http.client.requests` (timing metrics) and `http.server.requests` (inbound
  metrics) to avoid naming collisions in shared Prometheus/Grafana environments.
- The maximum response body size for `responseCode` extraction defaults to 1 MB; this is large
  enough to cover typical JSON API envelopes while bounding per-call memory cost.

## Clarifications

### Session 2026-08-27

- Q: What should the default maximum response body size be for `responseCode` extraction attempts, above which the starter skips parsing and logs `responseCode=absent`? → A: Configurable with a built-in default of 1 MB.
- Q: Should the HTTP response status code group (2xx / 4xx / 5xx / network-error) be included as an additional metric label in v1? → A: Yes — include HTTP status code group as a third metric label alongside destination and outcome.
- Q: For a target URL like `https://payment-service.internal:8080/api/charge`, what exact string should appear as the destination service name in both the header and the metric label? → A: Use `host:port` (e.g., `payment-service.internal:8080`); if host is an IP address use `ip:port`; if the outgoing request already carries a `service_name` header, use that value instead (highest priority).
- Q: When a consuming service has not configured a Spring application name, what value should the starter use for the source-service header? → A: Use `"unknown"` as the header value and emit a WARNING-level log entry at startup; starter initialisation must not fail.
- Q: What should the default metric name prefix be for the call-outcome counters? → A: `http.outbound.calls` — distinct from Actuator's `http.client.requests` and `http.server.requests` to avoid naming collisions.
