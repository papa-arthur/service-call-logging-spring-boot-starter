# Research: Configurable Response Envelope Field Names

**Feature**: `specs/002-configurable-envelope-fields/spec.md`
**Created**: 2026-09-01

No `NEEDS CLARIFICATION` markers remain in the spec (three clarifications were resolved across
`/speckit-specify` and `/speckit-clarify` — see the spec's Clarifications section). This document
records the design decisions needed to turn the resolved spec into an implementable plan.

---

## 1. Configuration shape for the combination list

**Decision**: Add one new property, `service-call-logging.envelopes`, bound as
`List<ServiceCallLoggingProperties.Envelope>` (a new nested record alongside the existing
`Metrics` nested record), where `Envelope` has three constructor-bound fields: `codeField`
(`@DefaultValue "responseCode"`), `messageField` (`@DefaultValue "message"`), `successfulValue`
(`@DefaultValue "0"`). The list itself defaults to empty.

**Rationale**: This is the smallest addition consistent with the starter's existing
`@ConfigurationProperties` record style (`ServiceCallLoggingProperties.Metrics` is the direct
precedent for a nested, constructor-bound, `@DefaultValue`-annotated record). Spring Boot's
relaxed binder applies `@DefaultValue` per-field even to elements of a bound list, which means
FR-006 (per-field fallback when only one of the two names is configured for a combination) and
FR-007 (default successful value when omitted) fall out of the binder for free — no custom
fallback code is needed for a *partially*-specified entry. A wholly *unconfigured* service (empty
list) is handled by the extraction engine falling through to the same built-in default values
(see §3), so FR-005/FR-015 hold too.

**Alternatives considered**:
- *A `Map<String, Envelope>` keyed by an arbitrary name* — rejected: the spec's clarification was
  explicit that combinations are not bound to any identity (destination or otherwise); a map key
  would invite exactly that binding and adds a naming concept nothing in the spec asks for.
- *A single combined "envelope" property block plus a separate override list* — rejected: two
  configuration surfaces for one concept is unjustified complexity (Principle X) when one ordered
  list already covers both the single-combination case (a one-element list) and the
  multi-combination case (US1–US3) uniformly.

---

## 2. Where the matching/extraction engine lives

**Decision**: Introduce one new internal class, `EnvelopeFieldExtractor`
(`com.bookit.servicecalllogging.extractor`), constructed from the bound `Envelope` list plus an
`ObjectMapper`. It parses a response body once and returns a new record, `EnvelopeMatch(Integer
rawCode, int successfulValue, String message)`:
- Evaluates the configured `Envelope` list in order, then the built-in default
  (`responseCode`/`message`/`0`) as an implicit final entry; the first whose `codeField` is
  present with a numeric value in the body is the match (FR-004/FR-005).
- `rawCode` is that matched entry's code value, or `null` if nothing matched at all — including
  the default (FR-010).
- `successfulValue` is always populated from whichever entry matched (or `0` if none did),
  independently of which `ResponseCodeExtractor` bean is active (this is what lets FR-014's
  classification apply even under a consumer's custom extractor).
- `message` is read from the matched entry's `messageField`, independently of whether the code
  field parsed cleanly (FR-011/FR-012); a non-string value is treated the same as absent.

The existing `JacksonResponseCodeExtractor` (the default `ResponseCodeExtractor` bean) becomes a
thin adapter: `extract(bytes)` delegates to `EnvelopeFieldExtractor` and returns
`Optional.ofNullable(match.rawCode())`. Its public shape —
`Optional<Integer> extract(byte[] bodyBytes)` — does not change.

**Rationale**: This is the one design choice the spec's Clarification session pinned down
precisely (Q1: independent message extraction; Q2: unbound, ordered, content-matched list), so
the engine is written to match that decision literally rather than approximating it. Keeping the
engine as a plain internal class — not a new public SPI — honors Q1's rejected alternative
("redefine the extractor interface") and Principle X: nothing in the spec asks for a
message-specific override point, so none is added. `EnvelopeMatch` carrying `rawCode` as well as
`successfulValue`/`message` means the *default* path only ever parses the body once per
`EnvelopeFieldExtractor` call — the interceptor/filter call `EnvelopeFieldExtractor` for
message+successfulValue and, for the default extractor, get the code from the very same call via
`JacksonResponseCodeExtractor`'s delegation. A **custom** `ResponseCodeExtractor` still supplies
its own code independently, exactly as it does today (FR-016); the engine's `EnvelopeMatch` still
runs against the same bytes purely to supply the message and the successful-value classification.

**Alternatives considered**:
- *Fold message extraction into the `ResponseCodeExtractor` SPI by widening its return type* —
  this is the option the clarification session explicitly rejected: it would break every existing
  custom `ResponseCodeExtractor` implementation, a MAJOR-version, breaking change the spec's own
  goal ("no code changes beyond configuration") directly argues against.
- *A brand-new public SPI for message extraction, parallel to `ResponseCodeExtractor`* — rejected
  under Principle X: nothing in the spec calls for consumers to plug in custom message logic; the
  configured-field-name mechanism is the only extensibility this feature asks for.

---

## 3. Single body read, two JSON parses on the default path (accepted, benchmark-gated)

**Decision**: Do not attempt to share one parsed JSON tree between the code path and the message
path in this iteration. `BufferingClientHttpResponse` (blocking) and the reactive filter's bounded
prefix accumulator already read the response body **once** into an in-memory `byte[]` prefix for
telemetry purposes; that same `byte[]` is simply handed to *two* independent calls — the wired
`ResponseCodeExtractor.extract(bytes)` and the new `EnvelopeFieldExtractor.extract(bytes)` — each
doing its own `ObjectMapper.readTree(bytes)`. No additional body I/O is introduced; the added cost
is a second, bounded (≤ `max-body-bytes`), in-memory JSON parse.

**Rationale**: This is the simplest implementation that satisfies every FR, and it keeps the two
call sites fully decoupled — which matters because when a consumer's *custom* `ResponseCodeExtractor`
is active, there is no shared tree to give it anyway (it receives raw bytes exactly as today, per
FR-016). Sharing a parsed tree between the two calls only pays off on the default-extractor path,
and doing so would require either widening the (protected) SPI or introducing internal-only
plumbing whose complexity is not justified until measurement shows it is needed.

**Gate**: The project already carries a documented, measured overhead budget (`OverheadBenchmark`
— README: "under 10 µs per call for envelopes up to a few KB") as a direct expression of
Constitution Principle VI (bounded cost, documented). This feature's task list MUST include
re-running `OverheadBenchmark` after implementation and updating that documented figure. If the
measured overhead on the default path meaningfully regresses against a re-justified budget, the
follow-up is to share one parsed tree between `JacksonResponseCodeExtractor` and
`EnvelopeFieldExtractor` (an internal-only change; the public `ResponseCodeExtractor` SPI is
unaffected either way) — deferred until real numbers justify it, not designed in speculatively.

---

## 4. Log field naming: `responseMessage`, distinct from the configured `message-field` default

**Decision**: The new log line field is named `responseMessage`. This is a different namespace
from the *configured default field name* the starter reads from a downstream body (`message`) —
one is this starter's own log key (mirrors the existing `responseCode` log key exactly), the other
is the default name of a field in someone else's JSON. Conflating them would make the contracts
doc and the log output harder to read side by side.

**Rationale**: Mirrors the existing `responseCode` → logged as `responseCode` precedent as closely
as possible (`<downstream code field default> = "responseCode"` and it is logged as
`responseCode=`; symmetric choice: `<downstream message field default> = "message"` logged as
`responseMessage=`).

**Alternatives considered**: Logging it simply as `message=` was rejected — SLF4J structured log
lines conventionally reserve a bare `message`-shaped key for the log line's own text in many
appenders/pipelines; reusing it for a business payload value risks collisions in consumers' log
processing rules that this starter has no visibility into.

---

## 5. Metrics: `message` MUST NEVER become a tag

**Decision**: The extracted message is a logged field only. It is explicitly excluded from
`OutboundCallMetrics` — no new Micrometer tag is added for it, and none should ever be added.

**Rationale**: Micrometer/Prometheus tag values are label dimensions; a free-text, unbounded,
per-call-unique string used as a tag value causes unbounded cardinality growth, which is a known,
severe operational hazard (memory growth in the registry, cardinality explosions in the scrape
backend). This is not a judgment call the spec left open — FR-013 through FR-015 describe the
message only as a *logged* value, never as a metrics dimension, and Constitution Principle VI's
"bounded cost" guarantee would be violated by an unbounded-cardinality tag. Documented here as a
hard constraint precisely because it is the kind of thing an implementer could add "for
consistency" with the other classification fields without realizing the operational consequence.

---

## 6. Constitution Principle VII (Data Hygiene) — proposed amendment required

**Finding**: Principle VII currently states the logged field set is "fixed and exhaustive: the
source/destination correlation header values and the parsed `responseCode` integer." FR-013
(message logged verbatim, no bound — per the specify-phase clarification) adds a field outside
that enumerated set. This is a genuine, non-negotiable-principle conflict, not a Principle X
(YAGNI) complexity deviation, so it cannot be waived via this plan's Complexity Tracking section —
the constitution's own governance rules route a substantive change to a NON-NEGOTIABLE principle
through the Amendment Procedure (a proposal, review, and ratification, ordinarily via
`/speckit-constitution`), not through a feature plan.

**Proposed amendment** (classified MINOR — existing compliant behaviour remains compliant; no
consumer action required):

> The complete set of fields that MAY be emitted is fixed and exhaustive: the source/destination
> correlation header values, the parsed `responseCode` integer, and — when a message field is
> configured or defaulted — the extracted response message string, logged verbatim with no length
> bound or content filtering. This is a deliberate, documented exception to the bounded-size norm
> expressed elsewhere in this principle, accepted because the message is an adopter-configured,
> expected diagnostic field rather than incidental data; adopters who configure a message field
> against a downstream API that may echo sensitive content are responsible for that choice.

The static ArchUnit rule backing this principle (`DataHygieneArchTest`) must also be updated to
permit the new field name once the amendment is ratified.

**Status**: Not applied by this plan. Flagged as a blocking item for the user to ratify via
`/speckit-constitution` before `/speckit-tasks`/`/speckit-implement` ships FR-013. See the plan's
Constitution Check section.

---

## 7. Known limitation: combinations matched by field presence, not by extractor identity

**Decision**: Document (README + this feature's contracts) that a consumer using a fully custom
`ResponseCodeExtractor` whose code is not sourced from a same-named JSON field at all (the
existing contract doc's own "non-JSON protocol" example) does not gain configurable-successful
-value classification from this feature unless the response body *also* happens to satisfy one of
the configured combinations' code fields. In that case classification silently falls back to the
built-in default successful value (`0`).

**Rationale**: Matching (§2) is defined purely by probing the JSON body for a configured field
name; a fully custom extractor that does not use field-name-shaped JSON at all gives the matching
engine nothing to match against, so it always falls through to the default. This is an inherent,
acceptable consequence of the content-matching model the clarification session chose over
identity-based (destination-bound) matching — not a defect — and is cheap to document exhaustively
alongside this starter's existing five enumerated known limitations.
