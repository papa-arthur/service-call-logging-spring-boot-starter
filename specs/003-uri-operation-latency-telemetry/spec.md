# Feature Specification: Outbound Call URI, Operation and Latency Telemetry

**Feature Branch**: `003-uri-operation-latency-telemetry`

**Created**: 2026-09-03

**Status**: Draft

**Input**: User description: "Extend the outbound service-call logging starter, building on its
existing call-log-record and correlation-header instrumentation, so that every outbound call's
observability data includes the destination service's URI and the URI of the inbound request the
consuming service is currently handling when it makes that call, exposed both as tags on a
Micrometer metric and as fields in the starter's outbound-call log entries, because operators need
to see call behaviour broken down by which of their own endpoints triggered a downstream call and
which downstream call was made, not only which service was called; the metric must also expose the
distribution of call latency through defined buckets so percentile- and SLO-style dashboards are
possible, with default bucket boundaries that remain configurable via application properties,
consistent with the starter's existing configuration approach; separately, consumers must be able
to attach a piece of metadata naming the business operation a given call represents, supplied as a
single fixed request header, \"X-Operation\" (for example, \"X-Operation: SendMoney\"), which is NOT
configurable via application properties because it is supplied per call by the caller rather than
representing a per-service identity, and which the starter carries through as both a tag on the
metric and a field in the log entries, falling back to the literal value \"undefined\" when a
consumer does not supply it, so the operation dimension is never sometimes-present and
sometimes-absent for the same metric; because the entire purpose of tagging by operation, per the
SendMoney example, is to see how much of that operation's traffic is succeeding versus failing, the
metric's tags must also let an operator distinguish successful from unsuccessful outcomes using the
same success/unsuccessful classification the starter already applies to the response code, rather
than introducing a separate notion of outcome; this feature also supersedes the starter's previous
single-combined-log-record behaviour, since the request itself must now also be logged: each
outbound call must produce two distinct, separately identifiable log entries rather than one — one
entry emitted when the request is sent, identified by beginning with \"outbound-request\" and
carrying the correlation headers, both URI tags, and the operation metadata as known at send-time,
and a second entry emitted once the response is received, identified by beginning with
\"outbound-req-response\" (replacing any prior \"outbound-call\" naming) and carrying that same
context plus the observed response code; The URI must be recorded in the templated form if possible
else in the raw form if not able to resolve to the templated form and must be consistent with the
starter's existing non-intrusion guarantee, no part of this instrumentation — reading the operation
header, resolving a URI, recording the metric, emitting either log entry — may throw, block, or
alter the outcome of the call it is observing"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Break Down Call Behaviour by Caller Endpoint and Called Endpoint (Priority: P1)

An operator watching a consuming service in production sees that calls to a downstream service are
failing, but cannot tell which of their own endpoints is generating those calls, nor which
downstream endpoint is being hit — the telemetry names only the downstream service. With this
feature, every outbound call's telemetry carries two endpoint identities: the URI of the downstream
call being made, and the URI of the inbound request their own service was handling when it made
that call. The operator can now slice the same telemetry by "which of my endpoints" against "which
of their endpoints".

**Why this priority**: This is the stated reason the feature exists — the existing
destination-service dimension is too coarse to locate a problem. Every other dimension this feature
adds is an additional axis on the same telemetry; this one is the axis the operator is missing
today.

**Independent Test**: Can be fully tested by having a stub inbound endpoint of the consuming
service make a call to a stub downstream endpoint, then asserting that both the metric tags and
both log entries name the inbound endpoint's URI and the downstream call's URI — each in templated
form where a template is available and in raw form where it is not.

**Acceptance Scenarios**:

1. **Given** a consuming service handling an inbound request to one of its own endpoints, **When**
   that request's handling makes an outbound call, **Then** the telemetry for that outbound call
   records both the inbound request's URI and the outbound call's destination URI.
2. **Given** an outbound call made against a URI template with variables filled in, **When** the
   call is instrumented, **Then** the recorded destination URI is the templated form, not the form
   with the variable values substituted.
3. **Given** an outbound call whose templated form cannot be determined, **When** the call is
   instrumented, **Then** the recorded destination URI is the raw form of that call's URI.
4. **Given** an outbound call made where no inbound request is being handled (for example from a
   scheduled task or during startup), **When** the call is instrumented, **Then** the inbound-URI
   dimension is still present, carrying the documented fallback value rather than being omitted.
5. **Given** any outbound call, **When** its telemetry is recorded, **Then** the two URI values
   appear both as tags on the metric and as fields in both of the call's log entries.

---

### User Story 2 - Attribute Calls to a Named Business Operation (Priority: P2)

A team wants to answer a business question — "how much of our SendMoney traffic is succeeding
versus failing?" — rather than an infrastructure question. The calling code supplies the operation
name on the outgoing request as a fixed header, `X-Operation: SendMoney`, and the starter carries
that name through into the metric and the log entries alongside the existing success/unsuccessful
classification. A call that does not name an operation still reports the dimension, using a
documented fallback value, so a dashboard never sees the dimension appear and disappear for the
same metric.

**Why this priority**: This dimension is what turns the telemetry from infrastructure monitoring
into business-outcome monitoring, which is the stated purpose of tagging by operation. It depends
on nothing in User Story 1 and is independently valuable, but the endpoint breakdown is the gap
that prompted the feature.

**Independent Test**: Can be fully tested by making two outbound calls, one carrying
`X-Operation: SendMoney` and one carrying no such header, then asserting that the metric and both
log entries report the supplied operation name for the first call and the documented fallback value
for the second, and that both calls' telemetry also carries the success/unsuccessful classification.

**Acceptance Scenarios**:

1. **Given** an outgoing request carrying `X-Operation: SendMoney`, **When** the call is
   instrumented, **Then** the metric and both log entries report the operation as `SendMoney`.
2. **Given** an outgoing request carrying no operation header, **When** the call is instrumented,
   **Then** the metric and both log entries report the operation as the literal `undefined`.
3. **Given** an outgoing request carrying an operation header whose value is empty or whitespace,
   **When** the call is instrumented, **Then** the operation is reported as the literal `undefined`.
4. **Given** a consuming service that tries to change the operation header's name through
   application properties, **When** the service starts, **Then** no such property is recognised —
   the header name is fixed.
5. **Given** any two outbound calls for the same operation, one classified successful and one
   unsuccessful, **When** an operator queries the metric, **Then** the two are distinguishable by
   the starter's existing success/unsuccessful classification of the response code, with no
   separate notion of outcome introduced.
6. **Given** an outgoing request carrying an operation header, **When** the call reaches the
   downstream service, **Then** that header arrives exactly as the calling code set it — the
   starter neither removes, rewrites, nor adds it.

---

### User Story 3 - Build Percentile and SLO Dashboards from Call Latency (Priority: P3)

An operator needs to know not just how many outbound calls succeeded, but how slow they were —
"what is our p99 latency to this endpoint?" and "what share of SendMoney calls completed within
500 ms?". The metric records each call's elapsed time into defined latency buckets, so percentile
and SLO-style views can be derived for any combination of the dimensions above. Out of the box the
buckets are the metrics library's own latency defaults, which work unchanged and which the release
records concretely for the version it was verified against; a consuming service whose latency
profile differs can replace them through application properties.

**Why this priority**: Latency distribution is additive to the dimensions above — valuable, but the
dimensional breakdown is what makes the latency data actionable. Independently testable and
deliverable.

**Independent Test**: Can be fully tested by making outbound calls of known, differing durations
against a stub downstream service and asserting the metric reports counts in the expected buckets;
then by configuring non-default bucket boundaries and asserting the reported buckets change
accordingly.

**Acceptance Scenarios**:

1. **Given** a consuming service that has configured nothing, **When** outbound calls complete,
   **Then** their elapsed times are recorded into the metrics library's default latency buckets, and
   those boundaries match the concrete list the release documents for the library version it was
   verified against.
2. **Given** a consuming service that has configured its own latency bucket boundaries, **When**
   outbound calls complete, **Then** their elapsed times are recorded into the configured buckets
   instead of the defaults.
3. **Given** recorded latency data, **When** an operator queries the metric, **Then** percentile
   views and "share of calls faster than a given boundary" views can be derived for any combination
   of the destination URI, inbound URI, operation and success/unsuccessful dimensions.
4. **Given** an outbound call that fails before a response is received, **When** the call is
   instrumented, **Then** its elapsed time up to the failure is still recorded.

---

### User Story 4 - See the Request and the Response as Separate Log Entries (Priority: P3)

Today a call produces one log entry, written only after the response arrives — so a call that never
completes leaves no trace of having been attempted, and there is no record of what was sent. Each
outbound call now produces two separately identifiable entries: one written when the request is
sent, and one written when the response is received. An operator reading the log can see that a
call was attempted, with all its context, independently of whether it ever came back.

**Why this priority**: This replaces existing behaviour rather than adding a new dimension, and it
is a breaking change to the log surface consuming teams parse. It is independently testable and
valuable on its own.

**Independent Test**: Can be fully tested by making one outbound call and asserting exactly two
per-call telemetry entries are produced, the first beginning with `outbound-request` and the second
with `outbound-req-response`, that both carry the same call context, and that neither uses the
previous `outbound-call` naming.

**Acceptance Scenarios**:

1. **Given** any instrumented outbound call that the instrumentation observes without failing,
   **When** the call completes, **Then** exactly two per-call telemetry entries are produced for
   it — no more and no fewer.
2. **Given** an instrumented outbound call, **When** the request is sent, **Then** an entry
   beginning with `outbound-request` is produced carrying the correlation header values, both URI
   values, and the operation as known at send time.
3. **Given** an instrumented outbound call, **When** the response is received, **Then** an entry
   beginning with `outbound-req-response` is produced carrying that same context plus the observed
   response code.
4. **Given** an instrumented outbound call, **When** either entry is produced, **Then** neither
   entry uses the previous `outbound-call` naming, while the starter's separate
   instrumentation-failure warning entry keeps the identifying text it has today.
5. **Given** an outbound call that fails before any response is received, **When** the call is
   instrumented, **Then** the send-time entry has already been produced and the response entry is
   still produced, recording the response code as absent.

---

### Edge Cases

- **No inbound request in scope**: an outbound call made from a scheduled task, an application
  startup hook, or a background thread has no inbound request to name. The inbound-URI dimension
  must still be present with the documented fallback value, never absent.
- **Outbound call dispatched to a pooled or background thread**: a handler that hands work to an
  async task or a future may make the outbound call on a thread that previously served a *different*
  inbound request and may still hold that request's identity. The dimension must carry the fallback
  value rather than the stale value: a call tagged with the wrong endpoint is a defect, not degraded
  telemetry, because it sends an operator investigating an incident to code that is not involved.
  Distinguishing "no inbound request" from "inbound request not established" is not required — both
  report the same documented fallback.
- **Inbound request context not available on the reactive path**: where the inbound request being
  handled cannot be determined for a reactive outbound call, the inbound-URI dimension degrades to
  the fallback value; the call is unaffected and the remaining dimensions are still recorded.
- **URI carrying credentials or query parameters**: a destination URI may embed credentials in its
  userinfo component or carry tokens and personal data in its query string. Neither may reach a log
  entry or a metric tag. Recording the path component alone (FR-005) removes both by construction —
  userinfo sits in the authority and the query string is a separate component — rather than relying
  on a redaction step that could be missed for some way of supplying a URI.
- **High-cardinality raw URI**: when a templated form cannot be determined and the raw form embeds
  identifiers (for example an account number in the path), the raw value is per-identifier-unique.
  The log entries therefore carry the raw form, where the detail is wanted and costs nothing, while
  the corresponding metric tag carries a documented placeholder instead, so that a call whose
  template is unresolvable can never inflate metric cardinality.
- **Caller-supplied operation value with unbounded cardinality**: the operation header is free text
  supplied per call, so a consuming service could unintentionally send a unique value per call (for
  example one containing an account identifier). Two bounds apply together: a value must be within
  the documented length and character bound to be accepted at all, and the starter admits only a
  documented maximum number of *distinct* values per process — every further previously-unseen value
  is reported as the fallback. The shape bound alone is not sufficient, since values such as
  `SendMoney-0001`, `SendMoney-0002` satisfy it while still being unbounded in number.
- **Distinct-operation cap reached**: once a process has admitted its maximum number of distinct
  operation values, a genuinely new business operation introduced later in that process's life
  reports as `undefined` until the process restarts. An operator seeing an expected operation
  collapse into `undefined` should read it as the cap being reached, which is why the cap is a
  documented number rather than a silent internal limit.
- **Operation header supplied more than once** on the same outgoing request: a single value must be
  chosen deterministically rather than the dimension becoming multi-valued or absent.
- **Call that never completes**: a call that hangs until the caller times out still produces the
  send-time entry and, on failure, the response entry with the response code absent, plus a latency
  observation covering the elapsed time.
- **Latency buckets configured as an empty or invalid list**: the starter falls back to the metrics
  library's default latency distribution behaviour rather than failing startup or recording no
  latency at all — the same state as configuring nothing.
- **Metrics library upgraded without any change to the starter**: because the default boundaries are
  the library's rather than the starter's, an upgrade can silently change which buckets an
  unconfigured consumer gets. A consuming team that needs boundaries stable across upgrades pins
  them explicitly through configuration; the release documentation states this (FR-015).
- **Failure inside the new instrumentation itself** — the operation header cannot be read, a URI
  cannot be resolved, the metric cannot be recorded, or either log entry cannot be emitted: the
  business call proceeds and completes exactly as it would without the starter, with reduced
  telemetry only.
- **Consumer with no metrics facility present**: the log entries still carry all the new fields
  even though no metric is recorded.
- **Instrumentation-failure warning entry coexisting with the two telemetry entries**: a call whose
  instrumentation fails part-way can produce a warning entry in addition to whichever telemetry
  entries were emitted. The "exactly two entries" guarantee is a statement about the per-call
  telemetry entries, not about the total number of lines the starter can write for one call.
- **Response classified as neither successful nor unsuccessful**: a call whose response code cannot
  be determined must remain distinguishable from both a success and a failure on the metric, rather
  than being counted as either.
- **Consuming service with no log correlation configured**: both entries are still emitted with all
  their fields, but an operator reading interleaved concurrent calls cannot reliably tell which
  `outbound-request` line goes with which `outbound-req-response` line. This is an accepted,
  documented limitation of delegating pairing to the consumer's own correlation context (FR-035,
  FR-036), not a defect.

## Requirements *(mandatory)*

### Functional Requirements

#### Destination and inbound URI dimensions

- **FR-001**: The starter MUST record, for every instrumented outbound call, the URI of the
  destination of that call.
- **FR-002**: The starter MUST record, for every instrumented outbound call, the URI of the inbound
  request the consuming service is handling at the moment the call is made.
- **FR-003**: Each recorded URI MUST be the templated form of that URI whenever the templated form
  can be determined.
- **FR-004**: When the templated form of a URI cannot be determined, the starter MUST record that
  URI's raw form in the log entries, and MUST record a documented placeholder value in place of the
  raw form in the corresponding metric tag, so that an unresolvable template can never inflate
  metric cardinality.
- **FR-005**: Each recorded URI MUST be the path component only — no scheme, no host, no port, and
  no query string — in both the log entries and the metric tags. The destination service's identity
  is already carried by the starter's existing destination dimension and MUST NOT be duplicated
  into the URI dimension. A consequence of recording the path alone is that credentials embedded in
  a URI's userinfo component can never reach a log entry or a metric tag, since userinfo is part of
  the authority the recorded value excludes; this MUST hold regardless of how a URI was supplied.
- **FR-006**: When a URI cannot be determined at all — including when no inbound request is in
  scope — the starter MUST record the documented fallback value for that dimension, so that neither
  dimension is ever absent for some calls and present for others.
- **FR-007**: The starter MUST record an inbound-request URI only when the outbound call is
  demonstrably being made while that same inbound request is still being handled. Where that cannot
  be established — the call is dispatched to a background, pooled, or otherwise handed-off thread,
  or arises from a scheduled task or startup hook — the starter MUST record the documented fallback
  value and MUST NOT report a value it merely found associated with the executing thread. Reporting
  a stale inbound URI is a defect, not degraded telemetry: a wrong endpoint name sends an operator
  to innocent code during an incident, which is strictly worse than the dimension carrying its
  fallback.
- **FR-008**: The starter MUST NOT take on propagating the inbound request's identity across a
  thread hand-off in order to satisfy FR-007. Consistent with the starter's existing documented
  limitation that context propagation is the consuming service's responsibility, an
  async-dispatched call is expected to carry the fallback value.
- **FR-009**: Both recorded URIs MUST be exposed as tags on the metric and as fields in both of the
  call's log entries, subject to FR-004.

#### Latency distribution

- **FR-010**: The metric MUST record the elapsed duration of every instrumented outbound call,
  measured from the moment the request is sent to the moment the response is received or the call
  fails, as a distribution across defined bucket boundaries.
- **FR-011**: The distribution MUST allow percentile views and "share of calls within a given
  boundary" views to be derived for any combination of the metric's tags.
- **FR-012**: The starter MUST NOT define a bucket boundary list of its own. When the consuming
  service configures no boundaries, the starter MUST delegate to the metrics library's own default
  distribution behaviour, so that the out-of-the-box buckets are whatever that library considers
  appropriate for latency rather than a list this starter maintains.
- **FR-013**: The bucket boundaries MUST be replaceable through application properties, using the
  starter's existing configuration approach and prefix.
- **FR-014**: When the bucket boundaries are not configured, are configured as an empty list, or are
  configured with values the starter cannot use, the starter MUST fall back to the delegated library
  default of FR-012 and MUST NOT fail the consuming service's startup.
- **FR-015**: Because FR-012 delegates the default rather than fixing it, the release documentation
  MUST (a) state plainly that the effective default boundaries come from the metrics library, not
  from this starter; (b) name the library version the release was built and verified against; and
  (c) record the boundaries that version actually produces, so a consuming team has a concrete list
  to design dashboards against. It MUST also warn that upgrading that library can change the
  effective boundaries without any change to this starter, and that such a shift is therefore not
  signalled by this starter's own version number.
- **FR-016**: An elapsed duration MUST be recorded even when the call fails before a response is
  received.

#### Business operation dimension

- **FR-017**: The starter MUST read the business operation for a call from the request header
  `X-Operation` on the outgoing request.
- **FR-018**: The operation header's name MUST NOT be configurable through application properties.
- **FR-019**: When the operation header is absent, empty, or blank, the starter MUST record the
  operation as the literal value `undefined`.
- **FR-020**: The starter MUST accept as an operation only a value within a documented maximum
  length and a documented set of permitted characters, and MUST report any value outside that bound
  as the literal value `undefined`. The bound MUST NOT be configurable, since the dimension it
  protects is shared infrastructure rather than a per-service identity.
- **FR-021**: Because the length and character bound of FR-020 constrains a value's *shape* but not
  the *number* of distinct values a caller can invent, the starter MUST additionally admit no more
  than a documented maximum number of distinct operation values within one running process, and MUST
  report every further previously-unseen value as the literal value `undefined`. This cap MUST NOT
  be configurable, for the same reason FR-020's bound is not. Which operation values occupy the cap
  is first-come-first-served within a process: the starter MUST NOT attempt to rank, evict, or
  prefer values by call volume, since doing so would make a dimension's meaning change under load.
- **FR-022**: The operation value the starter records MUST be one and the same value in the metric
  tag and in both log entries; a value rejected by FR-020 or displaced by FR-021's cap MUST NOT
  appear in the log entries either.
- **FR-023**: The operation MUST be exposed as a tag on the metric and as a field in both of the
  call's log entries, for every call, so the dimension is never sometimes-present and
  sometimes-absent for the same metric.
- **FR-024**: The starter MUST NOT add, remove, or alter the operation header on the outgoing
  request; a value the calling code set MUST reach the destination unchanged — including a value
  FR-020 rejects for telemetry purposes — and the starter MUST NOT set one where the calling code
  set none.
- **FR-025**: When the operation header is present more than once on the same outgoing request, the
  starter MUST record exactly one deterministically chosen value.

#### Success/unsuccessful dimension

- **FR-026**: The metric's tags MUST let an operator distinguish successful from unsuccessful calls
  using the starter's existing classification of the response code, exposed under the same tag key
  and carrying the same set of tag values the starter already publishes today.
- **FR-027**: The starter MUST NOT introduce any notion of call outcome separate from that existing
  classification — no new tag key, no new tag value, and no renaming of an existing value. The
  existing classification distinguishes three states, not two: successful, unsuccessful, and
  "could not be determined" (a call whose response code was unavailable is neither successful nor
  unsuccessful). The metric MUST preserve all three rather than collapsing the third into either
  of the other two, so that an operator asking "how much of this operation's traffic succeeded?"
  is never silently shown undetermined calls as failures or as successes.
- **FR-028**: The starter's pre-existing outbound-call **volume** counter (named independently of
  the retired `outbound-call` log prefix — the two are unrelated) MUST also carry the
  destination-URI, inbound-URI and operation dimensions, so that both metrics the starter publishes
  offer the same breakdown. Its existing tags MUST be retained unchanged and none MUST be removed.
- **FR-029**: Adding tags changes the identity of the counter's series. Version 1.0.0 has never
  been published and the starter has no consumers, so there are no existing queries to break and
  no consuming teams to notify. The effect MUST still be documented — that queries aggregating
  away the counter's other tags keep working, while queries assuming its complete tag set do not —
  but as behaviour documentation for the first adopter, NOT as a migration note owed to anyone.

#### Two log entries per call

- **FR-030**: Each instrumented outbound call MUST produce exactly two per-call telemetry log
  entries: one when the request is sent and one when the response is received or the call fails.
  The starter's existing instrumentation-failure warning entry is not a per-call telemetry entry
  and is outside this count — it is emitted only when the instrumentation itself fails, and this
  feature neither renames nor removes it.
- **FR-031**: The send-time entry MUST be identifiable by beginning with `outbound-request`, and
  MUST carry the correlation header values, both recorded URIs, and the operation as known at send
  time.
- **FR-032**: The response entry MUST be identifiable by beginning with `outbound-req-response`, and
  MUST carry the same context as the send-time entry plus the observed response code.
- **FR-033**: Neither per-call telemetry entry MUST use the previous `outbound-call` naming; that
  naming is replaced for the per-call telemetry entry. This requirement governs the per-call
  telemetry entries only and MUST NOT be read as forbidding the starter's existing
  instrumentation-failure warning entry, whose identifying text also begins with the characters
  `outbound-call` and which is deliberately left unchanged (see FR-030).
- **FR-034**: When a call fails before a response is received, the response entry MUST still be
  produced, recording the response code as absent.
- **FR-035**: Pairing the two entries for one call MUST rely on the log-correlation context the
  consuming service already maintains — the trace and span identifiers its own logging setup stamps
  onto every line — rather than on any identifier this starter generates. The starter MUST NOT add a
  correlation field of its own to either entry, so the loggable-field set stays as FR-039 fixes it.
- **FR-036**: Because FR-035 delegates pairing to the consumer's log-correlation context, pairing is
  available only to a consuming service that has configured one. The starter MUST document that a
  consumer without log correlation, and a consumer on the reactive path (where the starter's existing
  documented limitation already places correlation propagation with the consuming service), cannot
  reliably pair the two entries under concurrency. This limitation MUST NOT be presented as a
  starter defect to be fixed later; it is the accepted cost of adding no field.

#### Non-intrusion and data hygiene

- **FR-037**: No step of this instrumentation — reading the operation header, resolving either URI,
  recording the metric, or emitting either log entry — MUST be able to throw into the business call
  path, alter the call's outcome, or alter the response the calling code receives.
- **FR-038**: No step of this instrumentation MUST perform blocking work or work whose cost grows
  with payload size; the send-time entry in particular MUST NOT delay dispatch of the request beyond
  the starter's documented per-call overhead budget.
- **FR-039**: The fields this feature adds to the log surface — the two URIs and the operation — MUST
  be the only additions; no header bag, request body, response body, credential, or cookie value may
  become loggable as a consequence.
- **FR-040**: All existing guarantees MUST continue to hold unchanged: the response body remains
  fully readable by the calling code, an unparseable or absent response code still degrades to
  absent, and the starter is still fully disable-able and overridable.

#### Documentation and compatibility

- **FR-041**: Every new configuration key, every new metric tag, every new log field, the new log
  entry prefixes, the operation length and character bound, and every fallback and placeholder
  value MUST be documented for consuming teams before release.
- **FR-042**: The change from one combined log entry named `outbound-call` to two entries named
  `outbound-request` and `outbound-req-response`, together with the added counter tags of FR-028,
  MUST be documented as a behaviour change. It is NOT released as a breaking change and owes no
  migration note: 1.0.0 is unpublished and there are no consumers whose log parsing, dashboards or
  alerts could depend on the previous entry or the previous tag set. The audience for this
  documentation is the first adopter, and anyone running the starter from source. Should 1.0.0 ever
  be published before this feature ships, this requirement reverts to its original form and a
  MAJOR release with a migration note becomes owed.

### Key Entities

- **Outbound call telemetry context**: everything known about one instrumented outbound call —
  the calling service name, the destination service name, the destination URI, the inbound request
  URI, the business operation, the elapsed duration, the observed HTTP status and its
  classification, and the response code with its success/unsuccessful classification. Both log
  entries and the metric are projections of this one context; the send-time entry projects the
  subset known before a response exists.
- **Latency bucket boundaries**: the ordered set of duration boundaries the elapsed duration is
  distributed across; supplied by the consuming service or defaulted by the starter.
- **Business operation**: a caller-supplied name for what a call represents in business terms,
  scoped to a single call rather than to the service; always present in telemetry, defaulting to
  `undefined`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can break outbound call volume down by which of their own endpoints made
  the call, which downstream endpoint was called, which business operation the call represents, and
  whether it succeeded — as four independent dimensions of the same metric, with no change to
  consuming service code.
- **SC-002**: Percentile views (for example p50, p95, p99) and SLO-style views (for example "share
  of calls completing within 500 ms") can be derived from the metric for any combination of those
  dimensions.
- **SC-003**: 100% of instrumented outbound calls produce exactly two per-call telemetry entries,
  distinguishable from one another by their opening text, and 0% produce a per-call telemetry entry
  with the previous `outbound-call` naming.
- **SC-004**: 100% of instrumented outbound calls report a value for each of the destination-URI,
  inbound-URI and operation dimensions — no call omits a dimension.
- **SC-005**: 100% of enumerated instrumentation failure modes — operation header unreadable, either
  URI unresolvable, metric recording failing, either log entry failing to emit — leave the business
  call's outcome, timing semantics, and response body byte-for-byte identical to an uninstrumented
  call.
- **SC-006**: A consuming service adopting this feature needs zero code changes to receive the URI,
  latency and success/unsuccessful dimensions, and needs only to set one request header per call to
  receive the operation dimension.
- **SC-007**: Per-call instrumentation overhead remains within the worst-case budget the starter
  publishes today (documented as under 10 µs per call). If the work this feature adds cannot fit
  that budget, a revised worst-case figure MUST be measured and published before release rather
  than the budget being left silently exceeded.
- **SC-008**: No log entry or metric tag produced by the starter contains a scheme, host, port,
  query string, embedded credential, or any other value outside the documented field set — verified
  automatically, not by inspection. Verified adversarially for the URI dimensions specifically: a
  call whose target URI carries userinfo credentials and a query string full of tokens produces a
  recorded URI containing neither.
- **SC-009**: The number of distinct values each new metric dimension can take is bounded by a
  documented number the starter enforces itself, not by caller input. Verified adversarially: a test
  that issues calls carrying a unique operation header value every time, and calls whose URIs
  cannot be templated, produces no more distinct tag values than the documented caps allow — the
  distinct-operation cap for the operation dimension, and the consuming service's own finite route
  and template inventory (plus the single documented placeholder) for the two URI dimensions.

## Assumptions

- **Placeholder for an unresolvable templated form**: the literal `unresolved` is assumed as the
  metric-tag placeholder required by FR-004, distinct from the `unknown` used when a URI cannot be
  determined at all, so an operator can tell "we could not template this" apart from "there was no
  URI here".
- **Operation bound values**: a maximum of 64 characters, restricted to ASCII letters, digits,
  `.`, `_` and `-`, is assumed as the documented bound required by FR-020. The length is generous
  enough for any descriptive operation name (`SendMoney`, `ReverseTransfer`) and the character set
  excludes the separators and whitespace that would break log-line parsing, while making an
  embedded identifier-bearing value unlikely to pass by accident.
- **Distinct-operation cap**: **100** distinct operation values per running process is assumed as
  the documented cap required by FR-021. It is comfortably above the number of named business
  operations a single service plausibly performs, so a well-behaved consumer never reaches it, while
  being low enough that a misbehaving caller cannot damage the metric store. The cap counts distinct
  values seen since the process started and is not configurable (FR-021).
- **Fallback value for an undeterminable URI**: the starter's existing convention for a name it
  cannot determine is the literal `unknown`, and this feature reuses it for both URI dimensions.
  The literal `undefined` is used only for the operation dimension, as the description specifies.
- **Templated form availability**: for an outbound call the templated form is available only when
  the calling code expressed its target as a template rather than as a fully-built URI; for the
  inbound request it is the endpoint pattern the consuming service's framework matched. Where
  neither is obtainable, the raw form is used in the log entries and the documented placeholder in
  the metric tag (FR-004).
- **Default latency bucket boundaries are delegated, not defined here**: the starter ships no
  boundary list of its own; unconfigured, the buckets are whatever the metrics library's own default
  latency distribution behaviour produces (FR-012), and they remain fully replaceable per service
  (FR-013). The trade accepted with this choice: the starter has nothing to maintain or justify and
  automatically tracks the library's conventions, but the effective default is a property of the
  library version on the consumer's classpath rather than of this starter, so it can move under a
  dependency upgrade. FR-015 exists to keep that from being invisible — the release records the
  concrete boundaries of the version it was verified against and warns that an upgrade can change
  them.
- **Latency measurement boundary**: elapsed duration is measured across the call as the consuming
  service experiences it — from the request being handed to the HTTP client to the response becoming
  available, or to the failure — and therefore includes connection acquisition and any client-side
  queueing.
- **Existing dimensions retained**: the calling-service and destination-service correlation
  identities, the HTTP status and its group classification, the response code, and the business
  message all continue to be recorded as they are today; this feature is additive to them except
  for the log entry naming and count (FR-042) and the counter's added tags (FR-028).
- **The inbound URI is the only dimension that degrades by execution context**: the destination URI,
  latency and operation dimensions behave identically however the call is made. The inbound-request
  URI degrades to the fallback whenever the starter cannot establish that the call is being made
  while that request is still being handled — on the reactive path, and on any thread hand-off
  (FR-007, FR-008). This is consistent with the starter's existing documented limitation that
  context propagation is the consuming service's responsibility, and it means a service that makes
  most of its outbound calls asynchronously will see most of its traffic under the fallback value.
  Correctness is preferred over coverage here deliberately.
- **Operation header is read, not required**: a consuming service that never sets the header is
  fully supported and sees the `undefined` value; the header is never mandatory and its absence is
  never an error or a warning.
- **Metrics remain optional**: as today, no metric is recorded when the consuming service has no
  metrics facility on its classpath; the log entries carry the new fields regardless.
- **The existing outcome classification is reused verbatim, wording included**: the starter already
  publishes this classification under a dedicated tag whose values are `success`, `failure` and
  `absent`. FR-026 and FR-027 are satisfied by reusing that tag and those three values unchanged.
  The feature description's "successful / unsuccessful" wording describes the distinction an
  operator needs, not a new set of labels: renaming `failure` to `unsuccessful` would be exactly
  the separate notion of outcome FR-027 forbids, and would break every dashboard already querying
  the published value.
- **New metric tag keys follow the existing configurable-tag-key convention**: every tag key the
  starter publishes today has a configurable key name under the existing property prefix, so the
  destination-URI, inbound-URI and operation tag keys are assumed to be configurable in the same
  way. This is the *key* name only, and does not contradict FR-018: the `X-Operation` request
  header the value is read from stays fixed, because it is a per-call input supplied by the caller,
  whereas a tag key is part of the metric's shape in the consuming service's own monitoring system.
- **Per-call overhead budget**: the starter currently publishes a worst-case budget of under 10 µs
  per call, which is the figure SC-007 measures against unless the release publishes a revised one.

## Out of Scope

- **Inbound-request instrumentation as a feature in its own right.** The inbound request's URI is
  read only to tag an outbound call. The starter does not begin logging inbound requests, timing
  them, or publishing metrics about them.
- **Renaming or restructuring the existing success/unsuccessful classification.** Its tag key and
  its three published values stay exactly as they are (FR-027).
- **Making the operation header name, or the operation cardinality bound, configurable.** Both are
  deliberately fixed, as is the distinct-value cap (FR-018, FR-020, FR-021).
- **Propagating the operation to the destination service as a starter behaviour.** The starter reads
  the header the calling code set and leaves it untouched; it never sets, forwards, or synthesises
  one (FR-024).
- **Reactive-path inbound-URI parity.** The inbound-request URI may degrade to the fallback value on
  the reactive path, consistent with the starter's existing documented limitation that
  log-correlation propagation on that path is the consuming service's responsibility.
- **A starter-generated per-call log identifier.** Pairing the two entries is delegated to the
  consuming service's own log-correlation context; the starter adds no correlation field and takes
  on no responsibility for making pairing work where the consumer has configured none (FR-035,
  FR-036).
- **Server-side percentile computation.** The starter publishes a bucketed distribution so a
  monitoring system can derive percentiles with its own functions; it computes and publishes no
  percentile values itself, consistent with the existing decision not to compute rates.

## Dependencies

- **Constitution Principle VII (Data Hygiene & Security)** fixes an exhaustive set of fields the
  starter may log. That set currently admits the correlation header values, the response code, and
  the extracted business message. This feature adds three fields to the log surface — the
  destination URI, the inbound request URI, and the business operation — and therefore requires the
  principle's field set to be amended before the implementation can merge, together with the
  automated check that enforces it. This is a governance gate on the plan, not an implementation
  detail.
- **Constitution Principle IV (Backward Compatibility & Semantic Versioning)** is **not engaged**.
  It governs compatibility with a *released* public surface, and 1.0.0 has never been published,
  so the log entry renaming in FR-033 and FR-042 and the added counter tags in FR-028 have no
  baseline to break and no consuming teams observing the previous naming or tag set. The version
  stays 1.0.0 and no migration note is owed. The changes that *would* be breaking against a
  published baseline are catalogued in `research.md` §10 and become live MAJOR triggers the moment
  1.0.0 is cut.
- **Existing instrumentation** for both the blocking and reactive HTTP client paths, the existing
  response-code classification, and the existing metric are all prerequisites this feature builds
  on rather than replaces.

## Clarifications

### Session 2026-09-03

Each decision below was required because no reasonable default existed — each one either overrides an
explicit instruction in the feature description or accepts a cost the description did not weigh.

- **Q: When a URI's templated form cannot be determined, does the raw form go into the metric tag as
  well as the log entries?** → A: No. The raw form is recorded in the log entries, where the detail
  is wanted and costs nothing; the corresponding metric tag carries a documented placeholder
  instead (FR-004). The feature description says "else in the raw form", which taken literally
  applies to both surfaces, but a raw URI embedding a per-request identifier would create one metric
  series per identifier — an unbounded-cardinality path that Constitution Principle VI (Bounded
  Cost) treats as a design decision, not a detail. The log surface honours the description's intent
  in full; only the metric tag is bounded.

- **Q: Is the caller-supplied operation value accepted verbatim, however long or however unique?** →
  A: No. Only a value within a documented, non-configurable maximum length and character set is
  accepted as the operation; anything outside it is reported as `undefined` (FR-020, FR-022). The
  description specifies `undefined` only as the fallback for an *unsupplied* header, but the value
  is caller-supplied free text on a shared metric dimension, so the same cardinality concern
  applies. The bound governs telemetry only: a value it rejects still reaches the destination on the
  outgoing header exactly as the calling code set it (FR-024).

- **Q: Does the starter's pre-existing outbound-call counter also gain the new dimensions, or is it
  left untouched?** → A: It gains them, retaining all its existing tags unchanged (FR-028). Both
  metrics the starter publishes therefore offer the same breakdown. The cost is that adding tags
  changes the identity of an already-published series, so the consequence for consuming teams'
  existing queries is called out explicitly (FR-029) and folded into the MAJOR-release migration
  note (FR-042).

- **Q: When one outbound call writes its `outbound-request` entry and then its
  `outbound-req-response` entry, what lets an operator tell those two lines belong to the same call
  under concurrency?** → A: The log-correlation context the consuming service already maintains —
  its own trace and span identifiers — not an identifier the starter generates. The starter adds no
  correlation field of its own, so the loggable-field set stays at the three fields this feature
  already adds and the Principle VII amendment does not have to grow (FR-035). The accepted cost is
  that pairing is unavailable to a consumer that has not configured log correlation, and on the
  reactive path where the starter's existing documented limitation already places correlation
  propagation with the consuming service; this is documented as a limitation rather than treated as
  a defect to fix later (FR-036).

- **Q: A caller can send `SendMoney-0001`, `SendMoney-0002` and so on — every value passing the
  length and character rule while still creating a new metric series each time. How is the operation
  dimension's series count actually bounded?** → A: By a second, distinct bound: the starter admits
  at most a documented number of distinct operation values per running process (assumed 100) and
  reports every further previously-unseen value as `undefined` (FR-021). This closes a real
  contradiction — FR-020's length and character rule bounds a value's *shape*, not the *number* of
  distinct values, so SC-009's bounded-cardinality claim was false as previously worded and has been
  rewritten to be verified adversarially. The accepted costs are that the starter must remember
  which values it has admitted, and that occupancy is first-come-first-served, so an operation
  introduced after the cap is reached reports as `undefined` until the process restarts. Ranking or
  evicting by call volume was rejected: it would make a dimension's meaning change under load.

- **Q: FR-012 requires documented default bucket boundaries, but the spec only called them "a
  conventional latency ladder". What should the shipped defaults be?** → A: The starter defines no
  boundary list at all and delegates to the metrics library's own default latency distribution
  behaviour (FR-012). Nothing for the starter to maintain or justify, and it tracks the library's
  conventions automatically. The accepted cost is that the effective default becomes a property of
  the library version on the consumer's classpath rather than of this starter, so it can move under
  a dependency upgrade without this starter's version changing — which sits awkwardly with
  Principle VIII's requirement that every key's default value be documented. FR-015 is the
  mitigation and is not optional: the release must state that the default is delegated, name the
  library version verified against, record the concrete boundaries that version produces, and warn
  that an upgrade can change them. A team needing boundaries stable across upgrades pins them
  through configuration.

- **Q: If a request handler hands work to a background or pooled thread and the outbound call
  happens there, what should the inbound-request URI tag say?** → A: The documented fallback. The
  starter reports an inbound URI only when it can establish that the call is being made while that
  same request is still being handled, and never reports a value it merely found sitting on the
  executing thread (FR-007). A pooled thread can still hold a previous request's identity, and a
  call tagged with the wrong endpoint is a defect rather than degraded telemetry — it sends an
  operator to innocent code mid-incident. The accepted cost is coverage: a service that dispatches
  most outbound calls asynchronously sees most of its traffic under the fallback. Propagating the
  request identity across a hand-off to recover that coverage was explicitly rejected (FR-008), as
  it is the context-propagation work the starter's existing limitation leaves with the consumer.

- **Q: Should the destination-URI tag repeat the host information the existing destination tag
  already carries, or only the path?** → A: The path component only — no scheme, host, port or query
  string, on either surface (FR-005). Host identity stays where it already lives, in the existing
  destination dimension, so the two tags do not duplicate each other and one logical service
  resolving to several hostnames does not multiply cardinality. It also makes the credential
  guarantee structural rather than procedural: userinfo is part of the authority the recorded value
  excludes, so there is no redaction step to forget for some way of supplying a URI. The accepted
  cost is that telling apart the same path on two different hosts requires reading the URI tag
  together with the destination tag.

### Codebase verification (2026-09-03)

Claims this spec makes about existing behaviour were checked against the current implementation
rather than assumed:

- The current per-call entry is named `outbound-call`, confirming the rename in FR-033 is a real
  breaking change to the log surface.
- The starter also emits a *separate* instrumentation-failure warning entry whose identifying text
  begins with the same `outbound-call` characters. FR-030 and FR-033 are therefore scoped to the
  per-call telemetry entries, so that neither requirement accidentally forbids or renames an
  existing, unrelated entry.
- One counter exists today, tagged with the destination service name, the outcome classification and
  the HTTP status group, each under a configurable tag key. The outcome classification publishes
  three values (`success`, `failure`, `absent`), not two — which is why FR-027 requires all three to
  be preserved rather than collapsed into a binary success/unsuccessful split.
- The existing configuration surface is a single property prefix with a nested metrics group holding
  the tag keys, which is the "existing configuration approach" FR-013 requires the latency bucket
  boundaries to follow.
- `unknown` is the starter's established value for a name it could not determine, confirming the
  fallback assumed for the URI dimensions is a reuse rather than a new convention.
