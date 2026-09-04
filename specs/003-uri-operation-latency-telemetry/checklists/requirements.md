# Specification Quality Checklist: Outbound Call URI, Operation and Latency Telemetry

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-03
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.

### Validation iteration 1 (2026-09-03)

**Failing**: "No [NEEDS CLARIFICATION] markers remain" — 3 markers open, all raised because a
reasonable default could not be chosen without changing scope or accepting an unbounded cost:

1. **Raw-URI fallback as a metric tag** (Edge Cases): recording a per-identifier-unique raw URI as
   a metric tag is an unbounded-cardinality path, which Constitution Principle VI (Bounded Cost)
   makes a real decision rather than a detail.
2. **Operation dimension cardinality bound** (Edge Cases): the operation value is caller-supplied
   free text, so the same unbounded-cardinality concern applies, and bounding it is observable
   behaviour a consuming team must be told about.
3. **Scope of the pre-existing counter** (FR-020 as then numbered): whether the existing counter
   gains the new tags or is left untouched changes the size of the change and the cardinality of an
   already-published metric.

**Resolved without a marker** (recorded in Assumptions instead): the fallback value for an
undeterminable URI (`unknown`, reusing the starter's existing convention), the default latency
bucket ladder, the latency measurement boundary, and reactive-path parity for the inbound URI.

### Validation iteration 2 (2026-09-03) — PASS

All three markers resolved by maintainer decision and encoded as requirements:

1. Raw form is recorded in the log entries; the metric tag carries a documented placeholder
   (assumed literal `unresolved`) — **FR-004**, with the distinction from `unknown` recorded in
   Assumptions.
2. The operation is bounded by a documented, non-configurable maximum length and character set
   (assumed 64 characters; ASCII letters, digits, `.`, `_`, `-`), with out-of-bound values reported
   as `undefined` — **FR-017**, **FR-018**. The header itself still reaches the destination
   untouched (**FR-020**), so the bound governs telemetry only, never the call.
3. The pre-existing counter also gains the destination-URI, inbound-URI and operation dimensions,
   retaining all its existing tags — **FR-024**, with the series-identity consequence called out
   for consuming teams in **FR-025** and folded into the MAJOR-release migration note (**FR-037**).

**Added while resolving**: **SC-009**, making the bounded-cardinality guarantee a measurable
outcome rather than an implicit one.

**Post-resolution counts**: 37 functional requirements (FR-001 to FR-037, contiguous), 9 success
criteria, 0 open clarification markers. All checklist items pass.

**Note on terminology**: `spec.md` names `X-Operation` and the `outbound-request` /
`outbound-req-response` log prefixes literally, and names assumed literals for the fallback,
placeholder and bound values. These are the feature's externally-observable contract — fixed by the
feature description or required for the requirements to be testable — not implementation choices,
so this does not violate the "no implementation details" item.

### Carried into planning

Two governance gates are recorded in the spec's Dependencies section and must be cleared by
`/speckit-plan`'s Constitution Check, not deferred:

- **Principle VII (Data Hygiene)** fixes an exhaustive loggable-field set that does not yet admit
  the two URIs or the operation. It needs the same kind of amendment that v1.1.0 made for the
  business message, plus an update to the automated check that enforces it.
- **Principle IV (Semantic Versioning)** makes this a MAJOR release: the log entry renaming and the
  counter's added tags are both breaking changes to the observable surface.

### Validation iteration 3 (2026-09-03) — PASS, after codebase verification

The spec's claims about *existing* behaviour were verified against the current implementation
rather than accepted from the previous iteration. Three conflicts were found and corrected; all
checklist items still pass.

**Corrected — requirement contradicted existing behaviour:**

1. **FR-026 / FR-029 forbade an existing, unrelated log entry.** The starter emits a separate
   instrumentation-failure warning entry whose identifying text also begins with the characters
   `outbound-call`. As previously worded, "exactly two log entries" and "MUST NOT emit any log entry
   beginning with `outbound-call`" would have required that entry to be removed or renamed — which
   this feature never intended and which would have removed the only signal that instrumentation had
   degraded. Both requirements are now scoped to the *per-call telemetry* entries, with the warning
   entry named as explicitly out of scope. SC-003, User Story 4's independent test, and its
   scenarios 1 and 4 were re-scoped to match, and an edge case records the distinction.

2. **FR-022 assumed a binary classification that is actually ternary.** The existing classification
   publishes three values (`success`, `failure`, `absent`), not two. A binary reading would have
   forced calls whose response code could not be determined to be reported as either successes or
   failures — silently corrupting the "how much of this operation's traffic is succeeding?" question
   the feature exists to answer. FR-023 now requires all three states to be preserved, and an edge
   case covers the third.

3. **The feature description's "successful / unsuccessful" wording risked being read as a rename.**
   Relabelling the existing `failure` value to `unsuccessful` would be precisely the separate notion
   of outcome FR-023 forbids, and would break dashboards already querying the published value. An
   assumption now records that the description's wording names the distinction an operator needs,
   not a new label set, and that the existing tag key and its three values are reused verbatim.

**Strengthened:**

- **SC-007 was not measurable** ("within the starter's documented budget" without naming it). It now
  measures against the currently published worst-case figure of under 10 µs per call, and requires a
  revised figure to be measured and published if this feature's work cannot fit it.
- **New metric tag keys**: an assumption records that they follow the existing configurable-tag-key
  convention, and explains why that does not conflict with FR-015 — a tag key is part of the metric's
  shape in the consumer's monitoring system, whereas the `X-Operation` header is a per-call input.
- **Out of Scope section added**, bounding six things a reader could otherwise reasonably infer:
  inbound-request instrumentation as its own feature, renaming the outcome classification, making
  the header name or cardinality bound configurable, the starter propagating the operation itself,
  reactive-path inbound-URI parity, and server-side percentile computation.
- **Clarifications section added**, recording the three prior decisions as questions with answers and
  stated costs — each one overrides an explicit instruction in the feature description (raw URI in
  metric tags; `undefined` only for an unsupplied header) or accepts a cost the description did not
  weigh (changing an already-published series' identity) — plus a codebase-verification subsection
  listing what was checked.

**Counts unchanged**: 37 functional requirements (FR-001 to FR-037, contiguous, verified with no
gaps), 9 success criteria, 0 open clarification markers.

### Validation iteration 4 (2026-09-03) — clarification session, PASS

No checkbox changed state (16/16 before, 16/16 after). Recorded here because the spec grew
materially and iteration 3's stated counts are now out of date.

**Five clarification questions asked and answered.** Two of them closed outright contradictions
that would otherwise have reached planning:

1. **FR-031 vs FR-034 (as then numbered) were mutually exclusive** — the spec required the two log
   entries to be pairable while forbidding any new log field. Resolved by delegating pairing to the
   consuming service's own log-correlation context, adding no field. The loggable-field set stays at
   three additions, so the Principle VII amendment does not have to grow. The cost — no pairing for
   a consumer without log correlation, or on the reactive path — is now an explicit requirement and
   an edge case rather than an unstated gap.
2. **SC-009's bounded-cardinality claim was false** — the length and character rule bounds an
   operation value's *shape*, not the *number* of distinct values, so `SendMoney-0001`,
   `SendMoney-0002`, … all passed while being unbounded. Resolved with a documented cap on distinct
   operation values per process (assumed 100); SC-009 rewritten to be verified adversarially.

Three further decisions: latency bucket defaults are delegated to the metrics library rather than
defined by the starter (with a mandatory documentation requirement added, since Principle VIII
requires a documented default and a delegated one can shift under a dependency upgrade); the
inbound-request URI reports its fallback rather than ever inheriting a possibly-stale value from a
pooled thread; and each recorded URI is the path component only, which also makes the
no-credentials guarantee structural rather than a redaction step.

**Re-checked after the session**: "Requirements are testable and unambiguous" and "Success criteria
are measurable" both continue to pass, and are better supported than before — SC-007 now names the
budget it measures against (under 10 µs per call) instead of referring to it vaguely, and SC-008 and
SC-009 now specify adversarial verification instead of asserting a property.

**Current counts**: 42 functional requirements (FR-001 to FR-042, contiguous, verified with no gaps
and no duplicates), 9 success criteria, 8 recorded clarification decisions, 0 open markers.
