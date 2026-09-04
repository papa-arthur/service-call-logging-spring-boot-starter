# Implementation Plan: Outbound Call URI, Operation and Latency Telemetry

**Branch**: `003-uri-operation-latency-telemetry` | **Date**: 2026-09-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-uri-operation-latency-telemetry/spec.md`

## Summary

Adds four dimensions to outbound-call telemetry — the destination URI's path, the path of the
inbound request being handled when the call was made, a caller-supplied business operation name
from the fixed `X-Operation` header, and a latency distribution — and splits the single per-call
log entry into two (`outbound-request` at send time, `outbound-req-response` on completion).
Latency lands on a new Micrometer `Timer`; the existing counter keeps every tag it has and gains
the three new dimensional tags. The success/unsuccessful axis is the starter's existing `outcome`
tag, reused verbatim with its three values.

Two design consequences dominate the work and are not visible from the spec alone:

1. **The blocking path cannot see a URI template.** Verified against Spring Framework 6.1.21: a
   `ClientHttpRequestInterceptor` is handed an already-expanded `URI` and has no attribute map to
   read a template from (`research.md` §1). Left alone, every `RestTemplate` call would report the
   `unresolved` placeholder, making the destination-URI tag a constant on the starter's primary
   client path and failing SC-001. The plan therefore decorates the `RestTemplate`'s
   `UriTemplateHandler` to capture the template, guarded by an expanded-URI equality check that
   makes a stale read structurally impossible. This is the plan's one Principle X deviation.
2. **The delegated latency-bucket default is a cardinality hazard.** `publishPercentileHistogram()`
   multiplies roughly 70 buckets across every tag combination; a modest five-dimension service
   reaches ~1.9M series (`research.md` §5). The decision to delegate stands, but the documentation
   duty is widened and the risk is raised to the maintainer below.

Target release: **1.0.0 — unchanged**. 1.0.0 was never published and the starter has no consumers,
so Principle IV has no released baseline to bind against and no version bump is owed
(`research.md` §10).
**Blocked on a constitution amendment** (Principle VII) before implementation may merge.

## Technical Context

**Language/Version**: Java 17 (unchanged)

**Build Tool**: Maven 3.8+ (unchanged)

**Framework**: Spring Boot 3.3.13 → Spring Framework 6.1.21, Micrometer 1.13.x (unchanged)

**Primary Dependencies**: **No new dependencies.** The inbound URI is read through
`RequestContextHolder`/`RequestAttributes`, both already in the optional `spring-web` the blocking
path requires. The best-matching-pattern attribute key and the WebClient template attribute key are
used as **literal strings** precisely so that neither `spring-webmvc` nor `jakarta.servlet-api`
becomes a compile dependency (`research.md` §2, §3).

**Storage**: N/A (stateless instrumentation), with one exception: a bounded in-memory set of at
most 100 admitted operation values per process (a few KB — `research.md` §6).

**Testing**: JUnit 5, AssertJ, `ApplicationContextRunner`, ArchUnit, JDK `HttpServer` stub, plus
the existing `OverheadBenchmark` (all unchanged; no new test infrastructure)

**Target Platform**: Any JVM-based Spring Boot 3.x service (unchanged)

**Performance Goals**: stay within the currently published budget of **under 10 µs/call**
(SC-007). New per-call work: one header read, two path extractions, one map lookup, one
`nanoTime()` pair, one `Timer` record, one extra log entry. `OverheadBenchmark` MUST be re-run and
the README figure updated — or a revised figure recorded — as a task in this feature.

**Constraints**: no instrumentation step may throw, block, or alter a call (Principle I, FR-037);
no new forced dependency (Principle II); the `logging` package must remain free of Spring HTTP,
Micrometer and Jackson types under the existing ArchUnit rule (`research.md` §8); no metric
dimension may be unbounded by caller input (SC-009, Principle VI).

**Scale/Scope**: additive change to a single-artifact starter. New main classes: 5
(`DestinationUriResolver`, `InboundUriResolver`, `OperationResolver`, `UriTemplateCapture`,
`CapturingUriTemplateHandler`). Changed main classes: 9. New nested config record components: 4
(`destinationUriTagName`, `inboundUriTagName`, `operationTagName`, `latencyBuckets`).
No new Maven module.

## Constitution Check

*Verified pre-design and re-verified post-design.*

| Principle | Verification |
|---|---|
| I. Non-Intrusion | Every new step — header read, both URI resolutions, cap lookup, timer record, both log entries — sits inside the guarded try/catch pattern already used throughout `OutboundCallInterceptor`/`OutboundCallExchangeFilter`. The send-time entry is emitted before dispatch but inside a guard, so it cannot delay or fail the call. Template capture clears its `ThreadLocal` in a `finally`. New non-intrusion cases enumerated in Test Strategy. |
| II. Zero Forced Footprint | No new dependency. Servlet and WebMVC attribute keys referenced as literal strings, never as types, so a `RestTemplate`-only batch service gains no servlet stack (`research.md` §3). Latency/tag behaviour still absent when the consumer has no `MeterRegistry`. |
| III. Auto-Configured, Fully Overridable | The three resolvers are `@ConditionalOnMissingBean` beans like every other starter bean; `service-call-logging.enabled=false` still disables the whole feature; new tag-key names and bucket boundaries are properties with safe defaults. |
| IV. Backward Compatibility | **Not engaged — nothing published yet.** 1.0.0 has never been released and there are no consumers, so there is no baseline to break and no one to migrate; the version stays 1.0.0 (`research.md` §10). japicmp stays legitimately skipped for the reason its own pom comment already gives — no published artifact to diff against. The four changes that *would* be breaking are catalogued in `research.md` §10 and become live MAJOR triggers the moment 1.0.0 is cut. |
| V. Test-First (TDD) | Every behaviour below is ordered failing-test-first in Test Strategy; the auto-config matrix gains rows for the new beans and must stay at **100%** coverage — the standing build-breaking gate. |
| VI. Graceful Degradation & Bounded Cost | Operation cap bounds one dimension by construction; FR-004's placeholder bounds the two URI dimensions to the consumer's finite route inventory; no new body I/O and no new buffering. **Partially at risk from the delegated bucket default — see Risk R1.** Overhead budget re-measured as a task. |
| VII. Data Hygiene | **CLEARED — see Constitutional Gate Finding below.** Three fields are added to a set the principle declared fixed and exhaustive; the amendment admitting them is ratified (constitution v1.2.0, itself made ratifiable by the v1.3.0 Amendment Procedure change — see tasks.md T003). |
| VIII. Documentation | `contracts/configuration.md`, `contracts/metrics-schema.md`, `contracts/log-entries.md` (this feature); README updates required in the same PR — new config keys, three new log fields (seven → ten), new entry prefixes, the operation bound and cap, every fallback/placeholder literal, the delegated-bucket caveat with its cardinality warning, and a re-measured overhead figure. |
| IX. Spec-Driven Traceability | Every decision here maps to an FR or SC in `spec.md`; no implementation detail was written back into the spec. |
| X. Simplicity / YAGNI | One deviation, justified in Complexity Tracking: the `UriTemplateHandler` decorator. Everything else is the minimum that satisfies the spec — no new SPI, no reactive inbound-URI propagation, no percentile computation, no starter-generated correlation id. |

### Constitutional Gate Finding — Principle VII (Data Hygiene) — RESOLVED

> **Update (post-ratification, recorded during `/speckit-analyze` remediation on 2026-09-04):**
> this gate is **CLEARED**, not blocking. The amendment described below was ratified as
> constitution v1.2.0, and the two-maintainer approval threshold step 1 originally called for was
> itself changed by v1.3.0 specifically to make that ratification possible on a single-maintainer
> project (see the constitution's Sync Impact Report). tasks.md's T003 records the gate as
> cleared. The section below is retained as the historical record of the finding and the argument
> that justified the amendment; it no longer describes an open blocker.

Principle VII (v1.1.0) fixes an exhaustive loggable-field set: the correlation header values, the
parsed `responseCode`, and the extracted business message. This feature adds three more — the
destination URI path, the inbound URI path, and the operation. As with spec 002's message field,
this is a substantive conflict with a **NON-NEGOTIABLE** principle and is therefore **not**
eligible for a Complexity Tracking justification; the constitution's Amendment Procedure is the
only correct path.

**This plan does not apply the amendment.** Exact proposed wording, its MINOR classification
(1.1.0 → 1.2.0) and the supporting argument are in `research.md` §9. The argument in short: unlike
the unbounded message string already admitted in v1.1.0, all three new fields are bounded by
construction — the URIs are path components only, so a credential in userinfo or a token in a query
string cannot reach them (FR-005), and the operation is constrained to `[A-Za-z0-9._-]{1,64}`.

**Sequencing that `/speckit-tasks` and `/speckit-implement` respected:**

1. Ratify the amendment via `/speckit-constitution` (originally specified as two maintainer
   approvals; actually ratified under the sole-maintainer threshold introduced by v1.3.0 — see
   the update above).
2. Only then extend `DataHygieneArchTest.theLoggedFieldSetIsExactlyTheOneTheConstitutionPermits`,
   whose permitted list is hard-coded and will otherwise fail the build the moment the record
   grows. Extending it *before* ratification would make the test assert something the constitution
   does not permit — the gate would pass while the governance question was still open, which is the
   one failure mode this gate exists to prevent.
3. Only then implement the fields.

Everything else in this feature — latency, the counter's new tags, both log-entry prefixes, the
metric tag keys — has no Principle VII exposure and can proceed in parallel.

## Risks

**R1 — the delegated latency-bucket default can destabilise a consumer's metric store.**
`publishPercentileHistogram()` expands to roughly 70 buckets, multiplied across every tag
combination; the worked example in `research.md` §5 reaches ~1.9M series for a service with 20
destination templates, 15 inbound templates, 5 operations. Real traffic does not fill the full
cross product, but the order of magnitude is what matters, and it lands on a consumer who
configured nothing — which is the trust relationship Principle I exists to protect, arriving via
Principle VI's bounded-cost rule.

The clarification decision to delegate the default stands and this plan implements it. Mitigations,
all mandatory: FR-015's documentation duty is widened to carry the cardinality arithmetic and to
recommend an explicit short bucket list for high-dimensionality services; the operation cap and the
URI placeholder bound three of the five factors. **A maintainer should confirm this trade before
`/speckit-implement`** — the alternative (a starter-defined ~10-boundary default) was the rejected
option in that clarification, and reversing it is a one-line change to FR-012 plus a documented
default list.

**Resolved (recorded retroactively — `/speckit-analyze` found T004 still open on 2026-09-04, after
Phase 6 had already shipped):** kept the delegated default. Phase 6 implemented
`publishPercentileHistogram()` as the no-configuration path exactly as designed here, T060 recorded
the verified metrics-library version and its concrete boundary list in README per FR-015, and T061
carries the cardinality warning into the Metrics and Grafana section. No maintainer raised the
alternative before implementation reached this point, so reversing FR-012 now would mean redoing
Phases 6 and 8; the trade is accepted as shipped.

**R2 — `CallLogger` subclasses silently lose the send-time entry.** A consumer who extends
`CallLogger` and overrides `log` keeps working but emits no `outbound-request` entry until they also
override `logRequest` (`research.md` §7). With nothing published there is no one to migrate, but
this is a live trap for the first adopter, so it must be explicit in the README's extension-point
documentation rather than left as a footnote.

**R3 — inheritable thread-context breaks the no-stale-inbound-URI guarantee.** FR-007 holds because
`RequestContextHolder` uses a non-inheritable `ThreadLocal` by default. A consumer that explicitly
enables inheritable thread-context could see a child thread inherit a parent request's attributes.
Documented as a known limitation rather than defended against.

## Project Structure

### Documentation (this feature)

```text
specs/003-uri-operation-latency-telemetry/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── configuration.md
│   ├── metrics-schema.md
│   └── log-entries.md
├── checklists/
│   └── requirements.md
├── spec.md
└── tasks.md             # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/com/bookit/servicecalllogging/
├── ServiceCallLoggingProperties.java          # CHANGED: Metrics gains 5 components
├── autoconfigure/
│   ├── ServiceCallLoggingAutoConfiguration.java     # CHANGED: 3 resolver beans
│   ├── RestTemplateInstrumentationConfiguration.java # CHANGED: customizer also decorates handler
│   └── WebClientInstrumentationConfiguration.java    # CHANGED: new ctor args
├── interceptor/
│   └── OutboundCallInterceptor.java           # CHANGED: 2 entries, latency, 3 dimensions
├── filter/
│   └── OutboundCallExchangeFilter.java        # CHANGED: same, reactive
├── logging/
│   ├── CallLogger.java                        # CHANGED: logRequest(); response prefix renamed
│   └── OutboundCallRecord.java                # CHANGED: +destinationUri, +inboundUri, +operation
├── metrics/
│   └── OutboundCallMetrics.java               # CHANGED: Timer added; counter gains 3 tags
├── operation/                                  # NEW package
│   └── OperationResolver.java                 # NEW: header read, shape bound, distinct cap
└── uri/                                        # NEW package
    ├── DestinationUriResolver.java            # NEW: template-or-raw → path
    ├── InboundUriResolver.java                # NEW: RequestContextHolder → pattern path
    ├── UriTemplateCapture.java                # NEW: ThreadLocal (template, expandedUri)
    └── CapturingUriTemplateHandler.java       # NEW: UriTemplateHandler decorator

src/test/java/com/bookit/servicecalllogging/
├── operation/OperationResolverTest.java              # NEW
├── uri/DestinationUriResolverTest.java               # NEW
├── uri/InboundUriResolverTest.java                   # NEW
├── uri/UriTemplateCaptureTest.java                   # NEW
├── metrics/LatencyDistributionTest.java              # NEW
├── metrics/OperationCardinalityTest.java             # NEW (SC-009 operation half, adversarial)
├── metrics/UriCardinalityTest.java                   # NEW (SC-009 URI half, adversarial)
├── logging/TwoLogEntriesTest.java                    # NEW (FR-030..FR-034)
├── security/UriDataHygieneTest.java                  # NEW (SC-008, adversarial)
├── security/DataHygieneArchTest.java                 # CHANGED: permitted field list (gated)
└── ...                                               # existing suites extended
```

**Structure Decision**: unchanged single-artifact Maven layout. Two new packages, `uri` and
`operation`, exist to keep resolution logic out of `logging` — the existing ArchUnit rule forbids
the `logging` package from depending on Spring HTTP, Micrometer or Jackson types, so the record must
receive plain `String`s that were resolved elsewhere (`research.md` §8).

## Test Strategy (TDD order — Principle V)

Each row's test is written and observed to fail before its implementation. Grouped so that a group
can be committed independently.

| # | Failing test first | Then implement | Traces to |
|---|---|---|---|
| 1 | `OperationResolverTest`: supplied / absent / blank / over-length / bad chars / repeated header / 101st distinct value | `OperationResolver` | FR-017..FR-025 |
| 2 | `UriTemplateCaptureTest`: capture, read, clear, stale-pair rejected on URI mismatch | `UriTemplateCapture`, `CapturingUriTemplateHandler` | FR-003, §1 |
| 3 | `DestinationUriResolverTest`: template → path; absolute template → path; raw URI → path; query and userinfo never present; unparseable template does not throw | `DestinationUriResolver` | FR-003..FR-005, FR-037 |
| 4 | `InboundUriResolverTest`: pattern present; no request context → `unknown`; pooled thread → `unknown`, never stale | `InboundUriResolver` | FR-006..FR-008 |
| 5 | `TwoLogEntriesTest`: exactly two telemetry entries; prefixes; send-time fields; response adds code; failure still emits response entry with `absent`; warn entry unchanged and uncounted | `CallLogger.logRequest`, prefix rename, call sites | FR-030..FR-034 |
| 6 | `LatencyDistributionTest`: delegated default buckets; configured SLOs override; empty/invalid → default, no startup failure; latency recorded on transport failure; publishes exactly three `outcome` tag values (`success`/`failure`/`absent`), none collapsed | `OutboundCallMetrics` timer | FR-010..FR-016, FR-026, FR-027 |
| 7 | Tag-set assertions in `MetricsIntegrationTest`: counter retains its three existing tags and gains three more; timer's tag set is identical to the counter's; end-state re-check that the outcome three-value guarantee (red-tested in row 6) survived the tag additions | counter and timer tag additions | FR-026..FR-029 |
| 8a | `OperationCardinalityTest` (US2): 10 000 unique operation values yield ≤ 101 distinct operation tag values; asserts nothing about URI tags | (passes once 1 lands) | SC-009 — operation half |
| 8b | `UriCardinalityTest` (US1): untemplatable URIs collapse to one `unresolved` tag value; a fixed template inventory yields one tag value per template | (passes once 3 lands) | SC-009 — URI half |
| 9 | `UriDataHygieneTest`: a call to a URI with userinfo credentials and a token-bearing query string emits neither anywhere | (passes once 3 lands) | SC-008, FR-005 |
| 10 | Non-intrusion additions to `NonIntrusionRestTemplateTest` / `NonIntrusionWebClientTest`: each new step made to throw in turn leaves the response byte-for-byte identical | guards at each new call site | FR-037, SC-005 |
| 11 | `ApplicationContextRunner` rows for the three new beans × enabled/disabled × consumer-override | auto-config wiring | Principle III, V |
| 12 | `DataHygieneArchTest` permitted-field list — **only after the amendment is ratified** | record field additions | Principle VII gate |

Re-run `OverheadBenchmark` last and update the README figure (SC-007).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| `CapturingUriTemplateHandler` + `UriTemplateCapture` `ThreadLocal` — two classes and a thread-bound hand-off purely to learn a string the framework already knows | Verified: Spring Framework 6.1.21 gives a `ClientHttpRequestInterceptor` no access to the URI template (`research.md` §1). Without capture, every blocking-path call reports the `unresolved` placeholder in its destination-URI metric tag, so the dimension is a constant on the starter's primary client and **SC-001 fails**. | *Do nothing*: FR-003/FR-004 compliant but fails SC-001 — the feature's headline outcome. *Heuristic path normalisation*: invents unrequested behaviour, is silently wrong for legitimately numeric segments, and yields a value that resembles a template without being the one the developer wrote. *Upgrade the Boot parent*: far larger blast radius for every consumer than a decorator confined to this starter. Staleness — the usual objection to a `ThreadLocal` — is eliminated structurally by the expanded-URI equality check, not by discipline. |
