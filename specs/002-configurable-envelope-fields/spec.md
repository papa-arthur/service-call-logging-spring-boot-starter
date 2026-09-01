# Feature Specification: Configurable Response Envelope Field Names

**Feature Branch**: `002-configurable-envelope-fields`

**Created**: 2026-09-01

**Status**: Draft

**Input**: User description: "Configurable Response Envelope Field Names
Extend the outbound service-call logging starter so that the pair of field names it reads from
a downstream response body to determine the success/unsuccessful code and its accompanying
message is configurable per consuming service via application properties, because different
external APIs the starter's adopters call use different field names for functionally the same
pair — for example some use "statusCode"/"message" where others use "responseCode"/
"responseDescription" — while the semantics stay the same (a numeric code where exactly one
defined value means successful and every other value the field can take, not only a single
fixed alternative, means unsuccessful, plus a human-readable description); the feature must
define what the starter logs and how it behaves when the configured field names are absent
from a given service's configuration (falling back to the current documented defaults), when a
response body uses different field names than configured (code treated as absent, per the
starter's existing graceful-degradation guarantee), when a service needs to call multiple
external APIs that each use a different field-name pair for their envelopes within the same
application, when the configured "successful" value itself differs from the starter's current
assumption of 0 for success, and when the observed code is neither the configured successful
value nor any value the starter has previously treated as the sole "unsuccessful" case — such a
code MUST still be logged and classified as unsuccessful rather than treated as absent or
unrecognized, since only the single successful value is privileged and every other numeric
value is a valid unsuccessful outcome — all without requiring any code changes in the consuming
service beyond configuration, and without specifying which configuration mechanism, parser, or
data structure is used to implement it."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Configure a Custom Field-Name Pair for a Non-Standard Envelope (Priority: P1)

A platform engineer's service calls an external API whose response envelope reports its outcome
using field names other than the starter's built-in defaults (for example, `statusCode` and
`message`, or `responseCode` and `responseDescription`). The engineer sets the code field name
and message field name via configuration properties. From then on, the starter reads the
outcome code and message using the configured names instead of the defaults — with the same
success-is-zero semantics as before — and nothing else about how the service is written changes.

**Why this priority**: This is the specific, reported pain point driving the feature — different
adopters' downstream APIs already use different but functionally equivalent field names, and
today only one fixed pair is supported. Every other capability in this feature builds on this
basic substitution.

**Independent Test**: Can be fully tested by configuring a custom code field name and message
field name as one combination, calling a stub external API that returns its outcome under those
names, and asserting the log entry and metrics reflect the code and message read from the
configured names — while a call whose response body does not use those names continues to use
the built-in default combination.

**Acceptance Scenarios**:

1. **Given** a consuming service has configured a custom code field name and message field name
   as one combination, **When** a call's response envelope uses those configured names, **Then**
   the log entry for that call reports the code and message read from those fields, classified
   using the built-in default successful value.
2. **Given** the same configuration, **When** that call's response has a code field holding the
   value that represents success, **Then** the call is classified as successful, identically to
   how the built-in default field name would have been classified.
3. **Given** the same configuration, **When** the response body is inspected by the calling code
   after the starter has processed it, **Then** the body is unchanged and fully readable,
   consistent with the starter's existing non-intrusion guarantee.

---

### User Story 2 - Configure the Successful Outcome Value (Priority: P2)

A platform engineer's service calls an external API whose envelope uses a code value other than
`0` to represent success (for example, `1` or `200`). The engineer configures that value via
properties, alongside that envelope's combination. From then on, the starter classifies calls
matching that combination as successful only when the observed code equals the configured value,
and as unsuccessful for every other observed code — not only a single previously-assumed failure
value.

**Why this priority**: Field-name substitution alone (User Story 1) does not help an adopter
whose success indicator itself differs from `0`; this is the second half of describing a
downstream envelope correctly, and is independently valuable for adopters who already use the
default field names but a different success code.

**Independent Test**: Can be fully tested by configuring a non-default successful value as part
of one combination, then exercising calls whose response bodies return the configured successful
value, the previously-assumed failure value, and a third, never-before-seen value — asserting the
first is logged and counted as successful and both others are logged and counted as unsuccessful
with their raw values intact.

**Acceptance Scenarios**:

1. **Given** a consuming service has configured a non-default successful value as part of one
   combination, **When** a call's response matches that combination and returns the configured
   value, **Then** the call is classified and logged as successful.
2. **Given** the same configuration, **When** a call matching that combination returns any code
   other than the configured successful value — including a value the starter has never
   previously been told to treat as a failure — **Then** the call is classified and logged as
   unsuccessful, and the raw observed value is logged.
3. **Given** the same configuration, **When** metrics are inspected after a mix of such calls,
   **Then** the success and failure counts for calls matching that combination reflect the
   configured classification, not the built-in default of zero-means-success.

---

### User Story 3 - Distinct Envelope Conventions for Multiple External APIs in One Service (Priority: P3)

A service calls several external APIs in the course of its normal operation, and each of those
APIs uses its own, different field-name-and-successful-value convention. The platform engineer
configures each convention as its own combination. From then on, every outbound call is
interpreted using whichever configured combination actually matches that call's own response
body, with no cross-contamination between combinations, and no additional code in the service
beyond the configuration itself.

**Why this priority**: This is the scenario that makes single-combination configuration (as in
User Stories 1 and 2) insufficient on its own for any service that acts as a hub calling multiple
heterogeneous downstream APIs — a common shape for adopters of this starter. It depends on User
Stories 1 and 2 already working for a single combination.

**Independent Test**: Can be fully tested by configuring at least two distinct combinations,
exercising calls whose response bodies each match a different one, and asserting each call's log
entry and metric reflect only the combination that matches its own response body, including when
both kinds of call happen in close succession.

**Acceptance Scenarios**:

1. **Given** a consuming service has configured two distinct field-name-and-successful-value
   combinations, **When** the service makes calls whose responses each match one of the two,
   **Then** each call's log entry reflects the code and message read using the combination that
   matches its own response body.
2. **Given** the same configuration, **When** one configured combination would classify a given
   raw numeric value as successful and the other configured combination would classify that same
   raw value as unsuccessful, **Then** each call is still classified strictly according to
   whichever combination actually matches its own response body, not by the raw value alone.
3. **Given** the same configuration, **When** the service makes a call whose response body
   matches neither configured combination, **Then** that call is interpreted using the built-in
   default combination, unaffected by the other configured combinations.

---

### User Story 4 - Safe Fallback When Configuration Is Absent, Partial, or Mismatched (Priority: P4)

A platform engineer relies on the starter's existing promise that misconfiguration or an
unexpected response shape never breaks a business call. This feature extends that same promise
to the new configuration surface: a service with nothing configured behaves exactly as before, a
partially-configured combination falls back per-field to the built-in default, and a response
that matches no configured combination — nor the built-in default — is treated as if the code and
message were both absent, never as an error.

**Why this priority**: The starter's founding guarantee is that observability infrastructure
never becomes a production incident. Every new configuration surface is a new opportunity to
misconfigure, so this feature is not safe to ship without this guarantee holding for the new
surface specifically — even though it is sequenced last as its own testable slice.

**Independent Test**: Can be fully tested by exercising, independently: (a) a service with no
configuration for this feature at all; (b) a combination that configures only one of its two
field names; (c) a response body that matches neither any configured combination's code field
nor the built-in default's; asserting in every case that the call completes normally, the
response is unmodified, and the log records `absent` for whichever value could not be determined.

**Acceptance Scenarios**:

1. **Given** a consuming service has not configured any combination, **When** it makes outbound
   calls, **Then** code and message are read using the built-in default field names and default
   successful value, identical to the starter's behaviour before this feature existed.
2. **Given** a consuming service has configured only a code field name as part of one combination
   (no message field name), **When** a call's response matches that combination, **Then** the
   message is read using the built-in default message field name, independently of the configured
   code field name.
3. **Given** a consuming service has configured one or more combinations, **When** a call's
   response body has no numeric value under any configured combination's code field, nor under
   the built-in default's code field, **Then** the log records the code and the message as
   absent, no exception is raised, and the response reaches the calling code unmodified.

---

### Edge Cases

- What happens when the configured message field is present but holds a non-string value (e.g.,
  a number or a nested object)? The message MUST be treated as absent, exactly as a non-numeric
  code field is treated as absent today.
- What happens when the code field is successfully read but the message field is not (or vice
  versa)? Each MUST be extracted and logged independently — a determinable code with an absent
  message, or an absent code with a determinable message, are both valid, expected outcomes. A
  combination's message field being absent MUST NOT by itself cause that combination to be
  treated as not matching (see FR-004) — only the code field's presence determines a match.
- What happens when a consuming service has already replaced the starter's extraction behaviour
  with its own implementation via the starter's existing extension point? That implementation
  MUST continue to take full precedence for the code, exactly as it does today. The starter's
  message extraction proceeds independently regardless, using whichever configured or default
  message field name applies — such a service gains message support the moment it adopts this
  feature, without changing its existing extractor (Clarification, 2026-09-01).
- What happens when a single response body's fields happen to satisfy more than one configured
  combination at once (for example, a body that legitimately or coincidentally carries both
  `statusCode` and `responseCode`)? The earlier combination in the consuming service's configured
  order MUST be used, deterministically (Clarification, 2026-09-01); a consuming service is
  responsible for ordering its configured combinations so that the more specific or more likely
  match is listed first.
- What happens when every configuration value for this feature is left at its default? Observable
  behaviour MUST be indistinguishable from the starter's behaviour before this feature existed.
- What happens to previously-recorded metrics when a service changes its configured successful
  value? Historical time series MUST NOT be rewritten; only calls observed after the change are
  classified under the new configuration.

## Requirements *(mandatory)*

### Functional Requirements

#### Envelope Configuration Surface

- **FR-001**: The starter MUST allow a consuming service to configure, via application
  properties, the name of the field it reads from a downstream response body to obtain the
  business outcome code, replacing the built-in default field name.
- **FR-002**: The starter MUST allow a consuming service to configure, via application
  properties, the name of the field it reads from the same downstream response body to obtain a
  human-readable message describing that outcome, replacing the built-in default field name.
- **FR-003**: The starter MUST allow a consuming service to configure, via application
  properties, the single numeric value of the code field that represents a successful outcome,
  replacing the built-in default successful value.
- **FR-004**: The starter MUST support configuring more than one such {code field name, message
  field name, successful value} combination, as an ordered list, within a single consuming
  service's configuration — without binding any combination to a particular destination. For
  each call, the starter MUST determine which combination applies by evaluating the configured
  combinations in their configured order and selecting the first one whose code field is present
  with a numeric value in that call's response body; a combination's message field being present
  or absent MUST NOT affect whether that combination is selected. This lets a service calling
  multiple external APIs, each using a different combination, have every call interpreted
  correctly purely by the shape of its own response body, with no additional identifying
  information needed from the call site.
- **FR-005**: When a response body has no numeric value present under the code field of any
  configured combination, the starter MUST interpret that response using the built-in default
  combination, exactly as if the consuming service had configured nothing.
- **FR-006**: When only one of the two field names (code, message) is configured for a given
  combination, the starter MUST fall back to the built-in default name for whichever field name
  was left unconfigured, independently of the other.
- **FR-007**: When a successful value is not configured for a given combination, the starter
  MUST use the built-in default successful value (`0`) for that combination.

#### Extraction & Classification Semantics

- **FR-008**: For any call, once the applicable combination is determined, the starter MUST
  attempt to read that combination's code field and message field from the top-level JSON object
  of the response body, independently of each other.
- **FR-009**: When the configured code field is present in the response body and holds a numeric
  value, the starter MUST classify the call as successful if that value equals the applicable
  combination's successful value, and MUST classify the call as unsuccessful for every other
  numeric value the field holds — not only a single previously-designated failure value. The raw
  numeric value observed MUST be logged in both cases.
- **FR-010**: When no configured combination's code field — including the built-in default's —
  holds a numeric value in the response body (for example, because the body is empty, non-JSON,
  or uses field names not covered by any configured combination), the starter MUST classify the
  call's code as absent, MUST NOT raise an exception, and MUST NOT prevent the response from
  reaching the calling code — consistent with the starter's existing graceful-degradation
  guarantee.
- **FR-011**: When the applicable combination's message field is present in the response body and
  holds a string value, the starter MUST capture that value as the call's message, independently
  of whether the code field was successfully read.
- **FR-012**: When the applicable combination's message field is absent from the response body or
  holds a non-string value, the starter MUST treat the call's message as absent, independently of
  whether the code field was successfully read, MUST NOT raise an exception, and MUST NOT prevent
  the response from reaching the calling code.

#### Logging & Observability

- **FR-013**: The starter MUST log the extracted message value verbatim, with no length limit, or
  `absent` when it could not be determined, alongside the fields it already logs for each call.
- **FR-014**: The starter's existing classification of call outcomes (success / unsuccessful /
  absent) for metrics purposes MUST continue to apply under the applicable configured successful
  value, so that a call classified as successful under a non-default configured successful value
  is counted as a success in metrics, and vice versa.
- **FR-015**: A consuming service that has not configured any combination for this feature MUST
  observe logging, metrics, and classification behaviour identical to the starter's behaviour
  before this feature existed.

#### Compatibility & Overridability

- **FR-016**: A consuming service that has already replaced the starter's response-code
  extraction behaviour via the starter's existing code-level extension point MUST continue to
  have its own implementation's return value used as the code, taking precedence over the value
  any matched combination's code field would otherwise have produced. The starter MUST still
  independently determine and attempt message extraction for that same call — using the same
  content-based matching described in FR-004 to select which combination's message field name
  applies — regardless of whether the code was ultimately obtained from the starter's own
  built-in logic or from the consumer's own implementation.
- **FR-017**: All new configuration keys introduced by this feature MUST be optional, with safe,
  documented defaults, consistent with the starter's existing configuration surface.
- **FR-018**: This feature MUST require no code change in a consuming service beyond
  application-property configuration to take effect.

### Key Entities

- **Envelope Field Configuration**: Represents one `{code field name, message field name,
  successful value}` combination in the consuming service's configured, ordered list. Exactly one
  combination is in effect for any given call — the first configured combination (in configured
  order) whose code field is present with a numeric value in that call's own response body, or
  the built-in default when none match. Combinations are not bound to any destination.
- **Outbound Call Outcome**: Extends the existing response-envelope concept with an independently
  -extracted message alongside the extracted code; each of the two may be present or absent
  independently of the other, and the code (when present) is classified as successful or
  unsuccessful against the combination in effect for that call.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A platform engineer configures a non-default code and message field-name pair as
  one combination via configuration only, and the resulting logs correctly reflect codes and
  messages read from that pair for any call whose response body matches it, with zero
  source-code changes to the consuming service.
- **SC-002**: A service calling three different external APIs, each with its own field-name and
  successful-value combination, produces correctly classified, correctly attributed log entries
  and metrics for all three simultaneously, with no observed cross-contamination between
  combinations across 100% of sampled calls.
- **SC-003**: 100% of calls whose response body does not match any configured combination
  continue to be interpreted with the built-in default combination, matching pre-feature
  behaviour exactly.
- **SC-004**: 100% of calls whose response body matches no configured combination and no
  built-in default — whether due to misconfiguration or a genuinely different envelope — result
  in the call being classified as absent for that field, with zero instrumentation exceptions and
  zero alteration to the response delivered to the caller.
- **SC-005**: Calls whose response body matches a configured combination with a non-zero
  successful value are correctly classified as successful when the observed code equals that
  value, and as unsuccessful for 100% of every other numeric value observed under that same
  combination, including values never seen before.

## Assumptions

- The response body is a single top-level JSON object; configured field names identify direct
  top-level fields of that object, consistent with the starter's current default extraction
  behaviour. Envelopes that nest the code or message under other structures remain the domain of
  the starter's existing code-level extension point, not this feature.
- The combination that applies to a given call is determined purely by the shape of that call's
  own response body, not by its destination: the starter evaluates the consuming service's
  configured combinations in their configured order and uses the first one whose code field is
  present with a numeric value, falling back to the built-in default when none match (
  Clarification, 2026-09-01 — supersedes the destination-keyed model considered earlier in
  specification). A consuming service is responsible for ordering its configured combinations so
  that any body capable of matching more than one of them resolves to the intended, earlier entry.
- The built-in default code field name remains `responseCode` and the built-in default
  successful value remains `0`, unchanged from today. This feature introduces a built-in default
  message field name, `message`, since no message field is read or logged today.
- A consuming service's own code-level replacement of the starter's extraction behaviour (the
  starter's existing extension point) is a full replacement for the code, as it is today, and
  takes precedence over the code value a matched combination would otherwise have produced.
  Message extraction proceeds independently of that override (Clarification, 2026-09-01), so such
  a service gains message support without changing its existing extractor.
- A message value is meaningful only when it is a string; a non-string value in the configured
  message field is treated as absent.
- This feature adds the extracted message to the set of values the starter may log, which today
  is fixed and limited to source, destination, method, HTTP status, HTTP status group, and code.
  Logging that message verbatim (Clarification, 2026-09-01) means the starter will log free-text
  content sourced from a downstream response body with no length bound and no content filtering.
  This MUST be reconciled with the starter's existing, non-negotiable data-hygiene guarantee
  during planning — at minimum, the plan MUST document this as a deliberate, accepted expansion
  of the fixed logged-field set (Constitution Principle VII), since neither an unbounded size nor
  the possibility of sensitive downstream content is currently within that guarantee's scope.
- The starter continues to target the same consuming-service population, HTTP client types, and
  operating assumptions established for the starter's existing behaviour.

## Clarifications

### Session 2026-09-01

- Q: The starter doesn't log any message today — only the numeric code. This feature adds
  message extraction, which means deciding how that free-text value is allowed into the starter's
  logs. Should the extracted message be logged verbatim, bounded, or excluded from the default
  log line? → A: Log it verbatim, with no length limit — matches how the raw numeric code is
  logged today.
- Q: If a consuming service already has its own code-level bean that fully replaces the starter's
  response-code extraction, should that service also get message extraction for free from the
  starter's own field-based logic, or no message until a future feature adds an equivalent
  override point? → A: Independent message extraction — the starter always attempts message
  extraction using the applicable configured/default field name, regardless of whether the code
  came from the built-in logic or a consumer's custom extractor; no existing extractor needs to
  change.
- Q: Should envelope combinations be keyed by destination service name (at either destination-only
  or destination-plus-path granularity), or bound to destinations at all? → A: Not bound to a
  destination. Configuration is a flat, ordered list of {code field name, message field name}
  combinations; for each call, the starter loops through the configured list in order and uses
  the first one that matches the actual response body, falling back to the built-in default when
  none match.
