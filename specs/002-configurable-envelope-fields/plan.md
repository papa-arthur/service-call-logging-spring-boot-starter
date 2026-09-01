# Implementation Plan: Configurable Response Envelope Field Names

**Branch**: `002-configurable-envelope-fields` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-configurable-envelope-fields/spec.md`

## Summary

Extends the starter so the pair of response-envelope field names it reads (currently the fixed
`responseCode`, plus a new `message` companion never extracted before) becomes configurable per
consuming service, as an **ordered, unbound list of `{code field, message field, successful
value}` combinations** — not keyed by destination. For each call, the starter walks that list and
uses the first combination whose code field is present with a numeric value in the response
body, falling back to the built-in default when none match. Message extraction and
successful-value classification run independently of whichever `ResponseCodeExtractor` is active,
so an adopter with an existing custom extractor gains message support with no code change. The
public `ResponseCodeExtractor` SPI does not change shape. Target release: **1.1.0** (additive,
non-breaking).

## Technical Context

**Language/Version**: Java 17 (unchanged)

**Build Tool**: Maven 3.8+ (unchanged)

**Framework**: Spring Boot 3.3.x (unchanged)

**Primary Dependencies**: No new dependencies. Reuses `jackson-databind` (optional, already
present) for the new internal extraction engine; no new library is introduced.

**Storage**: N/A (unchanged — stateless instrumentation)

**Testing**: JUnit 5, AssertJ, `ApplicationContextRunner`, JDK `HttpServer` stub (all unchanged
from spec 001; no new test infrastructure required)

**Target Platform**: Any JVM-based Spring Boot 3.x service (unchanged)

**Performance Goals**: Stay within a re-measured, re-documented per-call overhead budget (current
documented figure: under 10 µs/call for envelopes up to a few KB). The default extraction path
adds one additional bounded, in-memory JSON parse per call (see `research.md` §3) — `Overhead
Benchmark` MUST be re-run and the README figure updated as part of this feature's tasks.

**Constraints**: No breaking change to the `ResponseCodeExtractor` SPI (Constitution Principle
IV); no new forced dependency (Principle II); message value MUST NEVER become a metrics tag
(unbounded-cardinality hazard — see `research.md` §5).

**Scale/Scope**: Additive change to an existing single-artifact starter. New: 2 main classes
(`EnvelopeFieldExtractor`, `EnvelopeMatch`), 1 new nested config record (`Envelope`). Changed: 8
existing main classes. No new Maven module.

## Constitution Check

*Verified pre-design and post-design.*

| Principle | Verification |
|---|---|
| I. Non-Intrusion | `EnvelopeFieldExtractor.extract` never throws (mirrors `ResponseCodeExtractor` contract rule 1 exactly); every new call site wrapped in the same guarded try/catch pattern already used throughout `OutboundCallInterceptor`/`OutboundCallExchangeFilter`; new non-intrusion cases added (malformed `envelopes` entry, matching-engine exception) |
| II. Zero Forced Footprint | No new dependency; `EnvelopeFieldExtractor` bean gated `@ConditionalOnClass(Jackson)` exactly like the existing default extractor |
| III. Auto-Configured, Fully Overridable | `EnvelopeFieldExtractor` is a normal `@ConditionalOnMissingBean` bean, consistent with every other starter-contributed bean; `service-call-logging.enabled=false` still disables everything, this feature included |
| IV. Backward Compatibility | `ResponseCodeExtractor` SPI shape unchanged (explicit clarification-session decision); all new config keys are additive with safe defaults; target version 1.1.0 (MINOR) |
| V. Test-First (TDD) | New behaviour ordered so failing tests precede implementation (see Test Strategy); auto-config matrix gains new rows, must stay at 100% coverage |
| VI. Graceful Degradation | Matching reuses the existing bounded, already-cached body prefix — no new body I/O, no new unbounded buffering; benchmark-gated tradeoff documented in `research.md` §3 |
| VII. Data Hygiene | **Conditional — see Constitutional Gate Finding below.** The message field is a genuine, deliberate expansion of the currently-ratified fixed logged-field set and requires a constitution amendment before this feature can ship as designed. |
| VIII. Documentation | `contracts/configuration.md`, `contracts/envelope-matching.md`, `contracts/response-code-extractor-spi.md` (this feature); README updates (new config section, new log field, three new known limitations, re-measured overhead figure) required in the same PR as the implementation, per Principle VIII |
| IX. Spec-Driven Traceability | Every design decision above maps to an FR in `spec.md`; no implementation detail leaks into the spec itself |
| X. Simplicity / YAGNI | No new public extension point added (explicit clarification-session decision); Complexity Tracking below is empty — the ordered-list-with-first-match design is literally what was specified, nothing more |

### Constitutional Gate Finding — Principle VII (Data Hygiene)

Principle VII currently states the logged field set is "fixed and exhaustive: the
source/destination correlation header values and the parsed `responseCode` integer." This
feature's FR-013 (message logged verbatim, no bound — resolved during `/speckit-specify`'s
clarification) adds a field outside that enumerated set. This is a substantive conflict with a
**NON-NEGOTIABLE** principle, not a Principle X complexity deviation, so it is **not** eligible
for a Complexity Tracking justification — the constitution's own Amendment Procedure is the
correct path.

**This plan does not apply the amendment.** It proposes exact wording (see `research.md` §6) for
ratification via `/speckit-constitution`. **`/speckit-tasks`/`/speckit-implement` should not ship
FR-013 until that amendment is ratified** — every other FR in this feature has no constitutional
conflict and can proceed independently.

## Project Structure

### Documentation (this feature)

```text
specs/002-configurable-envelope-fields/
├── plan.md                          # This file
├── research.md                      # Design decisions (§1–§7)
├── data-model.md                    # Entity model (delta over spec 001's)
├── quickstart.md                    # End-to-end validation guide
├── contracts/
│   ├── configuration.md             # New `envelopes` key + updated known limitations
│   ├── envelope-matching.md         # The matching/ordering rule, worked examples
│   └── response-code-extractor-spi.md  # SPI-unchanged statement + independence rules
├── checklists/
│   └── requirements.md              # Spec quality checklist (already passing)
└── tasks.md                         # Created by /speckit-tasks (not yet)
```

### Source Code (repository root) — changes only

```text
src/
├── main/java/com/bookit/servicecalllogging/
│   ├── ServiceCallLoggingProperties.java              # CHANGED: + envelopes: List<Envelope>, + Envelope record
│   ├── extractor/
│   │   ├── JacksonResponseCodeExtractor.java          # CHANGED: delegates to EnvelopeFieldExtractor
│   │   ├── EnvelopeFieldExtractor.java                # NEW: the matching + extraction engine
│   │   └── EnvelopeMatch.java                         # NEW: record {rawCode, successfulValue, message}
│   ├── autoconfigure/
│   │   ├── ServiceCallLoggingAutoConfiguration.java   # CHANGED: + EnvelopeFieldExtractor bean
│   │   ├── RestTemplateInstrumentationConfiguration.java   # CHANGED: + ObjectProvider<EnvelopeFieldExtractor>
│   │   └── WebClientInstrumentationConfiguration.java      # CHANGED: + ObjectProvider<EnvelopeFieldExtractor>
│   ├── interceptor/
│   │   ├── OutboundCallInterceptor.java               # CHANGED: calls EnvelopeFieldExtractor; builds message
│   │   └── BufferingClientHttpResponse.java           # CHANGED: exposes cached prefix bytes for reuse
│   ├── filter/
│   │   └── OutboundCallExchangeFilter.java            # CHANGED: mirrors interceptor's changes, reactive path
│   ├── logging/
│   │   ├── CallLogger.java                            # CHANGED: logs responseMessage=
│   │   └── OutboundCallRecord.java                    # CHANGED: + message field
│   └── metrics/
│       └── ResponseCodeResult.java                     # CHANGED: of(rawCode, successfulValue)
└── test/java/com/bookit/servicecalllogging/
    ├── extractor/
    │   └── EnvelopeFieldExtractorTest.java             # NEW: matching order, per-field fallback, defaults
    ├── (existing test classes touching the files above) # CHANGED: extended, not restructured
    └── security/DataHygieneArchTest.java               # CHANGED: permit the new logged field name
```

No new package, no new Maven module, no new top-level test directory — every change lands inside
the existing structure spec 001 established.

**Structure Decision**: Unchanged single Maven module (Principle X). This feature is additive
within the existing package layout; nothing about it justifies restructuring.

## Auto-Configuration Wiring (delta)

| Bean | Configuration Class | `@ConditionalOnClass` | `@ConditionalOnMissingBean` | Notes |
|---|---|---|---|---|
| `EnvelopeFieldExtractor` | Root | `ObjectMapper` (Jackson) | `EnvelopeFieldExtractor` | **NEW.** Built from `properties.envelopes()` + an `ObjectMapper`; always independent of `ResponseCodeExtractor`. |
| `JacksonResponseCodeExtractor` | Root | `ObjectMapper` (Jackson) | `ResponseCodeExtractor` | **CHANGED.** Bean method now also takes `EnvelopeFieldExtractor` as a parameter. |
| `OutboundCallInterceptor` | RestTemplate | `RestTemplate` (string) | `OutboundCallInterceptor` | **CHANGED.** Constructor gains `ObjectProvider<EnvelopeFieldExtractor>`, defaulting (when Jackson is absent) to a no-op that reports `successfulValue=0, message=null` — identical to today's behaviour. |
| `OutboundCallExchangeFilter` | WebClient | `WebClient` (string) | `OutboundCallExchangeFilter` | **CHANGED.** Same shape of change, reactive path. |

All other rows from spec 001's wiring table are unchanged.

## Instrumentation Design (delta)

### RestTemplate path — changed segment of `intercept()`

```
buffered      = new BufferingClientHttpResponse(rawResponse, maxBodyBytes)
codeResult    = buffered.peek(responseCodeExtractor)        // UNCHANGED call; default or custom bean
envelopeMatch = envelopeFieldExtractor.extract(buffered.cachedPrefixBytes())   // NEW — same bytes, no new I/O
                  .orElse(EnvelopeMatch.NONE)                                  // when Jackson absent

outcome = codeResult.rawCode() == null
            ? ABSENT
            : (codeResult.rawCode() == envelopeMatch.successfulValue() ? SUCCESS : FAILURE)   // FR-009/FR-014

record = new OutboundCallRecord(source, destination, httpMethod, statusCode, statusGroup,
                                 codeResult.rawCode(), envelopeMatch.message(), startedAt)     // FR-013

callLogger.log(record)
outboundCallMetrics.record(destination, outcome, statusGroup)     // UNCHANGED tag set — no message tag
```

Everything upstream (header stamping, transport-error handling, the guarded try/catch structure)
is unchanged; this segment replaces the current hardcoded `ResponseCodeResult.of(rawCode)` call
(hardcoded `successfulValue = 0`) with the configurable classification above.

### WebClient path

Symmetric change: `BoundedBodyPrefix.bytes()` (already accumulated for the existing
`responseCodeExtractor.extract(...)` call) is additionally passed to
`envelopeFieldExtractor.extract(...)`; the rest of `instrument()`'s guarded structure,
`onErrorResume` network-error handling, and body re-emission via `cachedBody` are unchanged.

### `BufferingClientHttpResponse` — new responsibility

Gains a way to expose the prefix bytes `peek()` already read, so a second, independent extractor
call can run against them without a second body read. (Exact method shape — e.g. a
`cachedPrefixBytes()` accessor vs. widening `peek()`'s own return type — is an implementation
decision for `/speckit-tasks`, not fixed here; either satisfies the "one body read, reusable
bytes" requirement.)

## Test Strategy (delta)

### New/updated unit tests

| Test | Class | Assertion |
|---|---|---|
| New | `EnvelopeFieldExtractorTest` | Ordered-list matching, per-field fallback (FR-006), default-value fallback (FR-007), ambiguous-body-earlier-wins ordering, non-string/non-numeric handling, never throws |
| Updated | `JacksonResponseCodeExtractorTest` | Delegates correctly through `EnvelopeFieldExtractor`; existing cases (empty/non-JSON/absent field) still pass unchanged |
| Updated | `OutboundCallInterceptorTest` / `OutboundCallExchangeFilterTest` | `message` flows into `OutboundCallRecord` independently of `responseCode`; classification honors a non-default `successfulValue` |
| Updated | `CallLoggerTest` | New `responseMessage=` field in the log line; `absent` sentinel when null |
| Updated | `OutboundCallMetricsTest` | Confirms **no** message-derived tag exists on the counter (negative assertion — guards `research.md` §5) |
| Updated | `DataHygieneArchTest` | Permits the new `message` field on `OutboundCallRecord`/`CallLogger` once the constitution amendment lands; MUST still reject anything else |

### New integration test

| Test | Assertion |
|---|---|
| Extends `RestTemplateIntegrationTest` / `WebClientIntegrationTest`, or a new `MultiEnvelopeIntegrationTest` | Two+ configured combinations, calls to bodies matching each, plus a call matching neither — asserts each is classified/logged using only its own matching combination (US3), with no cross-contamination |

### Non-Intrusion regression suite (additions)

| Test | Assertion |
|---|---|
| New case | `EnvelopeFieldExtractor` throws internally (simulated) → call still succeeds; `responseMessage=absent`; `responseCode` unaffected |
| New case | Malformed/inconsistent `envelopes` configuration → starter still starts, still instruments, degrades to defaults rather than failing |

### Auto-configuration matrix (must remain 100% coverage)

Add: `EnvelopeFieldExtractor` activates when Jackson is present and no override exists;
`EnvelopeFieldExtractor` does NOT activate when Jackson is absent (mirrors the existing
`JacksonResponseCodeExtractor` row exactly); message extraction still occurs when a consumer
supplies a custom `ResponseCodeExtractor` (directly tests FR-016 / the clarification-session Q1
decision).

### Performance

Re-run `OverheadBenchmark` after implementation; update the README's documented per-call overhead
figure to reflect the additional bounded JSON parse (`research.md` §3). This is a required task,
not optional polish — Constitution Principle VI requires the budget to stay documented and
current.

## Complexity Tracking

*No Principle X violations requiring justification.* The ordered-list-with-first-match design is
the literal mechanism the clarification session specified; no additional abstraction, extension
point, or configuration surface beyond FR-001 through FR-018 is introduced.

The one outstanding gate item (Principle VII / Data Hygiene) is **not** a Complexity Tracking
entry — it is a proposed constitution amendment, tracked in `research.md` §6 and the
Constitutional Gate Finding above, pending ratification via `/speckit-constitution`.

---

**Version**: 1.1.0 (proposed) | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)
