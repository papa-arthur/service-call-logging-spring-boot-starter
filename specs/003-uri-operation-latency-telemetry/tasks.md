---

description: "Task list for 003-uri-operation-latency-telemetry"
---

# Tasks: Outbound Call URI, Operation and Latency Telemetry

**Input**: Design documents from `specs/003-uri-operation-latency-telemetry/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: **REQUIRED, not optional.** Constitution Principle V (Test-First) is NON-NEGOTIABLE and
enforced at code review: the failing test MUST exist as a commit before the implementation commit
for the same behaviour. Every test task below must be run and **observed to fail** before its
implementation task begins.

**Organization**: Grouped by user story. Note the unusual dependency shape — a constitution gate
(Phase 3) blocks three of the four stories but **not** User Story 3, which is therefore the one
increment shippable while the amendment is pending.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1–US4, mapping to the spec's user stories
- **⛔**: Blocked on the Principle VII amendment being ratified

## Path Conventions

Single-artifact Maven project. Main code under `src/main/java/com/bookit/servicecalllogging/`,
tests under `src/test/java/com/bookit/servicecalllogging/`. Paths below are repo-relative.

---

## Phase 1: Setup

**Purpose**: Establish a known-good baseline and settle the two open decisions before any code moves.

- [X] T001 Run `mvn clean verify` from the repo root (`pom.xml`) and confirm a fully green baseline on the feature branch with zero skipped tests; record the current figure printed by `src/test/java/com/bookit/servicecalllogging/OverheadBenchmark.java` for later comparison
- [X] T002 Confirm `<version>` in `pom.xml` stays at `1.0.0`: 1.0.0 has never been published and the starter has no consumers, so Principle IV has no released baseline to bind against and this feature ships inside the not-yet-cut first release (research.md §10). No bump is owed.
- [X] T003 Principle VII amendment gate — **CLEARED**. The two-maintainer threshold was unsatisfiable on a single-author project, so the Amendment Procedure itself was amended (constitution v1.3.0) to require approval from *every active maintainer*, which one person can satisfy and which scales to two automatically. The v1.2.0 Principle VII expansion is ratified on that basis and recorded in the constitution's Sync Impact Report. Phase 3 may proceed.
- [X] T004 Obtain and record the maintainer decision on Risk R1 in `specs/003-uri-operation-latency-telemetry/plan.md`: keep the delegated latency-bucket default, or reverse FR-012 to a starter-defined list. If reversed, revise FR-012/FR-015 in `spec.md` and `contracts/configuration.md` before Phase 6. **Resolved** (recorded retroactively after `/speckit-analyze` found this task still open despite Phase 6 having already shipped): kept the delegated default — see plan.md's Risk R1 for the recorded rationale and README's `latency-buckets` section for the shipped documentation FR-015 requires.

**Checkpoint**: baseline green, version confirmed at 1.0.0 (no bump owed), both decisions recorded.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The configuration surface every story reads. No Principle VII exposure, so this phase
is **not** gated.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T005 [P] Write failing tests in `src/test/java/com/bookit/servicecalllogging/ServiceCallLoggingPropertiesTest.java` for the four new `metrics` keys: defaults (`destination_uri`, `inbound_uri`, `operation`, empty bucket list), explicit binding, blank tag-name rejection, and `latency-buckets` duration parsing; and assert that a property such as `service-call-logging.operation-header-name` binds to nothing and has no effect, proving the operation header's name is not configurable (FR-018)
- [X] T006 Add `destinationUriTagName`, `inboundUriTagName`, `operationTagName` and `latencyBuckets` components to the `Metrics` record in `src/main/java/com/bookit/servicecalllogging/ServiceCallLoggingProperties.java`, with `@DefaultValue`/`@NotBlank` matching the existing tag-name keys, and normalise a null bucket list to empty in the compact constructor
- [X] T060 Document the four new `metrics` configuration keys in `README.md` — name, type, default and effect for `destination-uri-tag-name`, `inbound-uri-tag-name`, `operation-tag-name` and `latency-buckets`, plus the delegated-default caveat and its cardinality warning — in the SAME pull request as T006. Also run the application with `latency-buckets` unset, capture the exact metrics-library version on the classpath and the exact boundary list it publishes, and record both verbatim in `README.md` (FR-015) — a caveat naming no version and no concrete boundaries does not satisfy FR-015. Constitution Principle VIII makes a configuration-key change without its README update a hard review-time blocker, so this cannot wait for Phase 8. Numbered T060 to avoid renumbering T009–T057; execute here, immediately after T006.
- [X] T007 [P] Extend `src/test/java/com/bookit/servicecalllogging/autoconfigure/ValidationProviderAbsentTest.java` to prove the new keys still bind when no Jakarta Validation provider is present (Principle II)
- [X] T008 Update every `Metrics` construction site in `src/test/java/com/bookit/servicecalllogging/testsupport/TestProperties.java` and any direct constructor callers, so the record's changed canonical constructor does not break the test tree
- [X] T058 Extract a single tag-assembly helper in `src/main/java/com/bookit/servicecalllogging/metrics/OutboundCallMetrics.java` that builds the `Tags` set every meter is registered with, replacing the inline `.tag(...)` chain in `record(...)`, so that any dimension added later appears on every meter at once rather than being enumerated per meter. Numbered T058 to avoid renumbering T009–T057; execute here in Phase 2, before T024, T034 and T039.

**Checkpoint**: configuration surface in place; User Story 3 can now proceed even if Phase 3 is still blocked.

---

## Phase 3: Constitution Gate — Loggable Field Set ⛔

**Purpose**: Admit the three new fields to the log surface. **Blocks User Stories 1, 2 and 4. Does
NOT block User Story 3.**

**⛔ DO NOT START until T003 confirms the amendment is ratified.** Ordering here is not stylistic:
extending the enforcing test before ratification would make the gate assert a permission the
constitution had not granted, which is the single failure mode that gate exists to prevent
(plan.md → Constitutional Gate Finding).

- [X] T009 ⛔ Extend the `permitted` list in `src/test/java/com/bookit/servicecalllogging/security/DataHygieneArchTest.java` with `destinationUri`, `inboundUri` and `operation`; run it and confirm it now fails against the unchanged record
- [X] T010 ⛔ Add `destinationUri`, `inboundUri` and `operation` as `String` components to `src/main/java/com/bookit/servicecalllogging/logging/OutboundCallRecord.java`, updating its javadoc to state the never-null/never-empty invariant and that all three are path-or-literal values only
- [X] T011 ⛔ Update `src/test/java/com/bookit/servicecalllogging/logging/OutboundCallRecordTest.java` for the new components, and confirm `DataHygieneArchTest` is green again — including the rule that keeps the `logging` package free of Spring HTTP, Micrometer and Jackson types

**Checkpoint**: the record can carry the new dimensions; US1, US2 and US4 are unblocked.

---

## Phase 4: User Story 1 - Break Down Call Behaviour by Caller Endpoint and Called Endpoint (Priority: P1) 🎯 MVP

**Goal**: Every outbound call's telemetry carries the destination URI path and the path of the
inbound request being handled, on both the metric and both log entries.

**Independent Test**: a stub inbound endpoint calls a stub downstream endpoint; assert both metric
tags and both log entries name the inbound pattern and the destination template — template form
where available, raw path in logs with `unresolved` in the metric tag where not, `unknown` where
no URI exists.

**Depends on**: Phase 2, Phase 3.

### Tests for User Story 1 ⚠️

> Write these FIRST and observe each one FAIL.

- [X] T012 [P] [US1] Write failing `src/test/java/com/bookit/servicecalllogging/uri/UriTemplateCaptureTest.java`: capture then read; clear after read; a stored pair whose expanded URI does not match the intercepted request URI is rejected rather than returned
- [X] T013 [P] [US1] Write failing `src/test/java/com/bookit/servicecalllogging/uri/DestinationUriResolverTest.java`: relative template → path; absolute template → path only; raw URI → raw path; query string and userinfo absent from every output; a template containing `{}` does not throw when path-extracted
- [X] T014 [P] [US1] Write failing `src/test/java/com/bookit/servicecalllogging/uri/InboundUriResolverTest.java`: best-matching-pattern present → that pattern; no request context → `unknown`; a thread carrying no request attributes → `unknown` and never a previous request's path
- [X] T015 [P] [US1] Write failing `src/test/java/com/bookit/servicecalllogging/security/UriDataHygieneTest.java` (SC-008, and required by the amended Principle VII Verification clause): a call to a URI carrying userinfo credentials and a token-bearing query string leaks neither into any log entry nor any metric tag
- [X] T059 [P] [US1] Write failing `src/test/java/com/bookit/servicecalllogging/metrics/UriCardinalityTest.java` (SC-009, URI dimensions, adversarial): calls whose URIs cannot be templated yield exactly one `unresolved` placeholder tag value rather than one series per raw path, and a fixed template inventory yields one tag value per template. Numbered T059 to avoid renumbering; execute alongside T012–T015.

### Implementation for User Story 1

- [X] T016 [P] [US1] Implement `src/main/java/com/bookit/servicecalllogging/uri/UriTemplateCapture.java`: a `ThreadLocal` holding a `(template, expandedUri)` pair, with read-and-clear semantics
- [X] T017 [US1] Implement `src/main/java/com/bookit/servicecalllogging/uri/CapturingUriTemplateHandler.java` decorating a delegate `UriTemplateHandler`, recording the pair on both `expand` overloads and never letting a capture failure affect expansion (depends on T016)
- [X] T018 [US1] Implement `src/main/java/com/bookit/servicecalllogging/uri/DestinationUriResolver.java` returning the two surfaces separately — log value (template path, else raw path, else `unknown`) and metric value (template path, else `unresolved`, else `unknown`) — extracting a template's path by string scan, never by URI parsing (depends on T016)
- [X] T019 [P] [US1] Implement `src/main/java/com/bookit/servicecalllogging/uri/InboundUriResolver.java` reading `RequestContextHolder` and the request-scoped attribute `org.springframework.web.servlet.HandlerMapping.bestMatchingPattern` through the `RequestAttributes` interface, using the attribute key as a literal string so neither `spring-webmvc` nor `jakarta.servlet-api` becomes a dependency
- [X] T020 [US1] Declare `DestinationUriResolver` and `InboundUriResolver` as `@ConditionalOnMissingBean` beans in `src/main/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfiguration.java` (depends on T018, T019)
- [X] T021 [US1] Extend `serviceCallLoggingRestTemplateCustomizer` in `src/main/java/com/bookit/servicecalllogging/autoconfigure/RestTemplateInstrumentationConfiguration.java` to wrap the template's existing `UriTemplateHandler` in `CapturingUriTemplateHandler` alongside adding the interceptor (depends on T017)
- [X] T022 [US1] Populate both URI values in `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java`, each resolution inside its own guard, clearing the capture `ThreadLocal` in a `finally` so nothing leaks to the next call on that thread (depends on T020, T021, T010)
- [X] T023 [US1] Populate both URI values in `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java`, reading the template from the `ClientRequest` attribute `org.springframework.web.reactive.function.client.WebClient.uriTemplate` as a literal key, and using the fallback for the inbound URI on this path (depends on T020, T010)
- [X] T024 [US1] Add the destination-URI and inbound-URI tags to the shared tag assembly in `src/main/java/com/bookit/servicecalllogging/metrics/OutboundCallMetrics.java`, using the configured tag keys and the **metric** surface value so an untemplatable URI contributes `unresolved`, never a raw path; the counter and, once it exists, the timer both inherit them from that assembly with no further change (FR-028; depends on T006, T018, T058)
- [X] T025 [P] [US1] Add `ApplicationContextRunner` rows in `src/test/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfigurationTest.java` for both resolver beans across enabled/disabled × consumer-override-present/absent, keeping the autoconfigure package at 100% coverage
- [X] T026 [US1] Add end-to-end assertions to `src/test/java/com/bookit/servicecalllogging/interceptor/RestTemplateIntegrationTest.java` (template captured) and `src/test/java/com/bookit/servicecalllogging/filter/WebClientIntegrationTest.java` (template from request attribute) — the two paths reach the same result by different means, so each needs its own proof; and add the inbound-URI async/reactive fallback to `README.md`'s known-limitations list in this same pull request (Principle VIII covers a known limitation exactly as it covers a configuration key)

**Checkpoint**: US1 fully functional and independently testable. **This is the MVP.**

---

## Phase 5: User Story 2 - Attribute Calls to a Named Business Operation (Priority: P2)

**Goal**: A caller names the business operation on a call via `X-Operation`; the starter carries it
into the metric and both log entries, falling back to `undefined`, with the dimension bounded.

**Independent Test**: two calls, one carrying `X-Operation: SendMoney` and one carrying nothing;
assert the metric and both entries report `SendMoney` and `undefined` respectively, and that both
also carry the existing success/unsuccessful classification.

**Depends on**: Phase 2, Phase 3. Independent of US1, US3, US4.

### Tests for User Story 2 ⚠️

- [X] T027 [P] [US2] Write failing `src/test/java/com/bookit/servicecalllogging/operation/OperationResolverTest.java` covering: supplied value; absent; empty; whitespace-only; 65 characters; a character outside `[A-Za-z0-9._-]`; header present twice → one deterministic value; and the 101st distinct value in one resolver instance → `undefined`
- [X] T028 [P] [US2] Write failing `src/test/java/com/bookit/servicecalllogging/metrics/OperationCardinalityTest.java` (SC-009, operation dimension, adversarial): 10 000 calls each carrying a unique operation value yield at most 101 distinct operation tag values. The URI half of SC-009 is covered by T059 in User Story 1, so this task asserts nothing about URI tags
- [X] T029 [P] [US2] Write a failing assertion in `src/test/java/com/bookit/servicecalllogging/interceptor/RestTemplateIntegrationTest.java` that the `X-Operation` header **arrives at the stub server byte-identical to what the caller set**, including a value the shape bound rejects for telemetry (FR-024)

### Implementation for User Story 2

- [X] T030 [US2] Implement `src/main/java/com/bookit/servicecalllogging/operation/OperationResolver.java`: `getFirst` header read, then a character-scan shape check (no regex — caller-controlled input must not meet a backtracking engine), then a `ConcurrentHashMap`-backed admission set capped at 100 with no eviction and no volume ranking, else `undefined`
- [X] T031 [US2] Declare `OperationResolver` as a `@ConditionalOnMissingBean` bean in `src/main/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfiguration.java` (depends on T030)
- [X] T032 [US2] Resolve and populate the operation in `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java` inside its own guard, reading the header without modifying it (depends on T031, T010)
- [X] T033 [US2] Resolve and populate the operation in `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java`, reading from `ClientRequest.headers()` without rebuilding the request's operation header (depends on T031, T010)
- [X] T034 [US2] Add the operation tag to the shared tag assembly in `src/main/java/com/bookit/servicecalllogging/metrics/OutboundCallMetrics.java` using the configured tag key, so the counter and, once it exists, the timer both inherit it (FR-028; depends on T006, T030, T058)
- [X] T035 [P] [US2] Add `ApplicationContextRunner` rows for the `OperationResolver` bean in `src/test/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfigurationTest.java` across the full enabled/disabled × override matrix

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 6: User Story 3 - Build Percentile and SLO Dashboards from Call Latency (Priority: P3)

**Goal**: The metric records each call's elapsed time as a bucketed distribution, so percentile and
SLO views are derivable; defaults are delegated to the metrics library and replaceable per service.

**Independent Test**: calls of known differing durations against a stub; assert the timer reports
counts in the expected buckets, then configure non-default boundaries and assert they change.

**Depends on**: Phase 2 only. **NOT gated by Phase 3** — no new logged field is involved, so this is
the increment that can ship while the amendment is pending.

### Tests for User Story 3 ⚠️

- [X] T036 [P] [US3] Write failing `src/test/java/com/bookit/servicecalllogging/metrics/LatencyDistributionTest.java`: unconfigured → the metrics library's default distribution is published and percentile/share-within-boundary views are derivable; `latency-buckets: [50ms, 200ms, 1s]` → those boundaries are used instead; and the timer publishes exactly three `outcome` tag values — `success`, `failure`, `absent` — each reachable and distinct, with no fourth value and none collapsed into another (FR-026, FR-027), asserted here so the red test precedes T039's implementation rather than arriving only as T051's later re-check
- [X] T037 [P] [US3] Write failing tests in `src/test/java/com/bookit/servicecalllogging/metrics/LatencyDistributionTest.java` that an empty `latency-buckets` list, and one containing unparseable, zero or negative values, fall back to the delegated default **and the application context still starts** — assert startup success explicitly, not only the bucket values (FR-014)
- [X] T038 [P] [US3] Write failing tests that an elapsed duration is recorded when the call fails before any response, in `src/test/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptorTest.java` and `src/test/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilterTest.java` (FR-016)

### Implementation for User Story 3

- [X] T039 [US3] Add the latency `Timer` named `<prefix>.latency` to `src/main/java/com/bookit/servicecalllogging/metrics/OutboundCallMetrics.java`, tagged from the shared tag assembly of T058 so it carries exactly whichever dimensions the counter currently carries — three today, six once US1 and US2 land — selecting `publishPercentileHistogram()` when no usable boundaries are configured and `serviceLevelObjectives(...)` when they are, discarding unusable entries rather than failing (depends on T006, T058)
- [X] T040 [US3] Record elapsed time with `System.nanoTime()` around dispatch in `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java`, including on the transport-failure path, keeping the existing `Instant` timestamp for the record (depends on T039)
- [X] T041 [US3] Record elapsed time in `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java` on both the success and `onErrorResume` paths (depends on T039)
- [X] T042 [P] [US3] Extend `src/test/java/com/bookit/servicecalllogging/autoconfigure/MetricsAbsentWhenNoRegistryTest.java` to prove no timer is registered when the consumer has no `MeterRegistry`, while logging continues (Principle II)

**Checkpoint**: US1, US2 and US3 all work independently.

---

## Phase 7: User Story 4 - See the Request and the Response as Separate Log Entries (Priority: P3)

**Goal**: Each outbound call produces two separately identifiable telemetry entries —
`outbound-request` at send time and `outbound-req-response` on completion.

**Independent Test**: one call produces exactly two telemetry entries with those prefixes, carrying
the same context, and no telemetry entry uses the old `outbound-call` naming.

**Depends on**: Phase 2, Phase 3. Best sequenced after US1 and US2 so the send-time entry can carry
their fields, but testable independently.

### Tests for User Story 4 ⚠️

- [X] T043 [P] [US4] Write failing `src/test/java/com/bookit/servicecalllogging/logging/TwoLogEntriesTest.java`: exactly two telemetry entries per call; first begins `outbound-request` and carries correlation values, both URIs and the operation but no status or response code; second begins `outbound-req-response` and adds the response code; a call failing before any response still emits the response entry with `responseCode=absent`; and, across every combination of no-inbound-request, untemplatable-destination, and no-operation-header, all three of `destinationUri`/`inboundUri`/`operation` are still present on both entries with their documented fallback rather than omitted (SC-004)
- [X] T044 [P] [US4] Write a failing test in `src/test/java/com/bookit/servicecalllogging/logging/TwoLogEntriesTest.java` that the instrumentation-failure warning entry keeps its exact `outbound-call-instrumentation-error` text and is **not** counted among the two telemetry entries (FR-030, FR-033) — this is the requirement most likely to be broken by an over-eager prefix rename
- [X] T045 [P] [US4] Extend `src/test/java/com/bookit/servicecalllogging/logging/CallLoggerTest.java` for `logRequest` output shape and the renamed response prefix

### Implementation for User Story 4

- [X] T046 [US4] Add `logRequest(OutboundCallRecord)` emitting the `outbound-request` prefix and rename the existing `log(...)` output prefix to `outbound-req-response` in `src/main/java/com/bookit/servicecalllogging/logging/CallLogger.java`, leaving `logWarn` and its text untouched
- [X] T047 [US4] Emit the send-time entry before dispatch in `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java`, inside the existing guarded pattern so a logging failure can neither delay nor break the call (depends on T046)
- [X] T048 [US4] Emit the send-time entry in `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java`, including on the early-return path taken when header stamping fails; and add the two-entry pairing limitation for consumers without log correlation to `README.md`'s known-limitations list in this same pull request (Principle VIII) (depends on T046)

**Checkpoint**: all four user stories independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T049 [P] Extend `src/test/java/com/bookit/servicecalllogging/interceptor/NonIntrusionRestTemplateTest.java`: make each new step throw in turn — operation header read, each URI resolution, capture read, timer record, each log entry — and assert every time that the response is byte-for-byte identical, the body fully readable, and any original exception propagates untouched; also assert that none of the new steps performs network I/O, disk I/O, or a wait/sleep — direct evidence for the no-blocking-work half of FR-038 that the aggregate overhead benchmark alone does not provide (FR-037, FR-038, SC-005)
- [X] T050 [P] Extend `src/test/java/com/bookit/servicecalllogging/filter/NonIntrusionWebClientTest.java` with the same per-step failure matrix on the reactive path
- [X] T051 Extend `src/test/java/com/bookit/servicecalllogging/metrics/MetricsIntegrationTest.java` to assert (a) the counter retains all three of its existing tags and gained exactly the three new ones, and (b) the timer carries a tag set identical to the counter's. The outcome-tag three-value guarantee on the timer already has its red test in T036 (FR-026, FR-027) — this task is the end-state re-check that the tag additions did not perturb it, not the first assertion of it. Counter-side three-value coverage already exists (`OutcomeTest.exposesExactlyThreeValues`, `MetricsIntegrationTest.absentResponseCodeIsCountedWithTheAbsentOutcome`, `threeSimultaneousCombinationsAreCountedIndependentlyAndCorrectly`) (FR-028, FR-029)
- [X] T052 Re-run `src/test/java/com/bookit/servicecalllogging/OverheadBenchmark.java` and either confirm the published under-10 µs/call budget still holds or publish a re-measured figure in `README.md` — leaving a figure the code exceeds is a Principle VIII violation (SC-007)
- [X] T053 Update `README.md`: the logged-field table from seven rows to ten, both new entry prefixes, the operation shape bound and the distinct-value cap, every fallback and placeholder literal (`unknown`, `unresolved`, `undefined`), and the two-entries-per-call log-volume note. The four configuration keys and the delegated-bucket caveat are already documented by T060, and the two known limitations by T026 and T048, under Principle VIII's same-pull-request rule — verify each is present rather than re-adding it
- [X] T061 Update the **Metrics and Grafana** section of `README.md`: it documented one counter and three tags, and covered neither the latency timer nor the three new dimensions, so the meter operators build percentile and SLO dashboards from was undocumented. Cover both meters (`<prefix>.total` and `<prefix>.latency`), all six tags with their value sets and what bounds each one, the `unresolved`-vs-raw-path asymmetry, the three distinct outcome values, and PromQL examples exercising the new dimensions and the timer. Also refresh the stale three-tag Prometheus sample in "What you get". Numbered T061 to avoid renumbering; execute with T053/T054. Added after `/speckit-analyze` found that no existing task covered this section (FR-041, Principle VIII).
- [X] T054 Document the pre-003 behaviour changes in `README.md` — as behaviour notes for the first adopter and for anyone running the starter from source, **not** as a migration note, since 1.0.0 is unpublished and there are no consumers to migrate (research.md §10): the `outbound-call` → `outbound-request`/`outbound-req-response` rename, doubled entry count, the counter's added tags and their series-identity consequence, the `CallLogger`-subclass caveat from research.md §7 (an override of `log` alone silently emits no send-time entry), and the pairing limitation for consumers without log correlation (FR-036, FR-042)
- [X] T055 Auto-configuration coverage gate — **the gate did not exist**; only compiler, surefire and japicmp plugins were configured, so the constitution's build-breaking 100% check had never been implemented (pre-existing debt from specs 001/002). Added JaCoCo 0.8.12 enforcing 100% INSTRUCTION/LINE/METHOD/BRANCH for `src/main/java/com/bookit/servicecalllogging/autoconfigure/` alone, and verified it fails the build on injected uncovered code. Closing the first gap it surfaced — the no-Jackson `ObjectProvider` fallback lambdas, invisible to a LINE-only rule — required `JacksonAbsentFallbackTest`; the package is now at 100% on every counter
- [X] T056 Run `mvn clean verify` and confirm the japicmp gate in `pom.xml` is still skipped for the reason its own comment gives — no published baseline artifact to diff against — and not because a real finding is being suppressed. With nothing published there is nothing to compare, so record-component changes to `src/main/java/com/bookit/servicecalllogging/logging/OutboundCallRecord.java` and `src/main/java/com/bookit/servicecalllogging/ServiceCallLoggingProperties.java` raise no Principle IV obligation (research.md §10)
- [X] T057 Walk `specs/003-uri-operation-latency-telemetry/quickstart.md` scenarios 1–7 end to end, including the manual `curl` bucket count against the cardinality arithmetic, and tick the release checklist
- [X] T062 Wire the auto-configured `DestinationUriResolver`, `InboundUriResolver` and `OperationResolver` singletons into `RestTemplateInstrumentationConfiguration.outboundCallInterceptor(...)` and `WebClientInstrumentationConfiguration.outboundCallExchangeFilter(...)` as `@Bean` method parameters, passing them to the interceptor's/filter's 9-arg constructor instead of letting the 6-arg overload silently default to `new DestinationUriResolver()`/`new InboundUriResolver()`/`new OperationResolver()`. Without this, `RestTemplate` and `WebClient` each got a private, unshared `OperationResolver`, so a consumer using both client types could admit up to 200 distinct operation values against FR-021's single-process cap of 100, and a consumer-supplied override of any of the three beans was silently ignored by both instrumentation paths — a live violation of Constitution Principle III (Auto-Configured, Fully Overridable). Strengthened `ServiceCallLoggingAutoConfigurationTest`'s three "consumer-supplied ... replaces the starter one" tests and `theOperationResolverIsASingletonSoOneCapCoversTheWholeApplication` to assert via `ReflectionTestUtils` that the interceptor and filter actually hold the registry's instance, not merely that the registry itself is a singleton — confirmed red against the pre-fix wiring (`git stash` of the two configuration classes), green after; `mvn clean verify` (including the JaCoCo auto-configuration-matrix gate) passes. Numbered T062 to avoid renumbering T009–T057; added after `/speckit-analyze` found plan.md's Project Structure predicted `WebClientInstrumentationConfiguration.java` would need "new ctor args" but no task ever assigned that wiring, and git status confirmed the file had never actually changed (FR-021, SC-009, Constitution Principle III)

---

## Dependencies & Execution Order

### Phase order

```
Phase 1 (Setup)
      ↓
Phase 2 (Foundational — config surface)
      ├──────────────────────────────────────────┐
      ↓                                          ↓
Phase 3 (⛔ Constitution Gate)            Phase 6 (US3 — latency)
      ├──────────────┬──────────────┐            
      ↓              ↓              ↓            
Phase 4 (US1)   Phase 5 (US2)   Phase 7 (US4)
      └──────────────┴──────────────┴────────────┘
                     ↓
              Phase 8 (Polish)
```

### The gate's practical consequence

**User Story 3 is the only story that can proceed while the Principle VII amendment is pending.**
It adds no logged field, so it has no Principle VII exposure. If ratification stalls, T036–T042
plus Phase 2 are a genuinely shippable increment; US1, US2 and US4 must wait on T003.

This inverts the usual priority order — the P1 story is gated and a P3 story is not — so do not
read the phase numbering as a schedule.

### Story independence

| Story | Depends on | Independent of |
|---|---|---|
| US1 (P1) | Phase 2, Phase 3 | US2, US3, US4 |
| US2 (P2) | Phase 2, Phase 3 | US1, US3, US4 |
| US3 (P3) | Phase 2 only | US1, US2, US4 — and the gate |
| US4 (P3) | Phase 2, Phase 3 | US1, US2, US3 (though its send-time entry is richer once they land) |

### Same-file contention — do NOT parallelise these

Four files are touched by several stories. Tasks within each group must be sequential even though
they belong to different stories:

- `OutboundCallInterceptor.java` — T022 (US1), T032 (US2), T040 (US3), T047 (US4)
- `OutboundCallExchangeFilter.java` — T023 (US1), T033 (US2), T041 (US3), T048 (US4)
- `OutboundCallMetrics.java` — T058 (Foundational, first), T024 (US1), T034 (US2), T039 (US3)
- `ServiceCallLoggingAutoConfiguration.java` — T020 (US1), T031 (US2)

---

## Parallel Execution Examples

### Phase 4 (US1) — write all five failing tests together

```bash
Task: "UriTemplateCaptureTest — capture, read-and-clear, stale-pair rejection"
Task: "DestinationUriResolverTest — template/raw/absolute, no query, no userinfo"
Task: "InboundUriResolverTest — pattern present, no context, no stale value"
Task: "UriDataHygieneTest — credential and token URI leaks nowhere"
Task: "UriCardinalityTest — untemplatable URIs collapse to one placeholder"
```

Then the two independent resolvers in parallel:

```bash
Task: "Implement UriTemplateCapture"        # T016
Task: "Implement InboundUriResolver"        # T019
```

### Across stories, once Phase 3 clears

```bash
Developer A: Phase 4 (US1) — T012–T026
Developer B: Phase 5 (US2) — T027–T035
Developer C: Phase 6 (US3) — T036–T042   # could have started during Phase 2
```

Coordinate the four contended files above at integration time.

---

## Implementation Strategy

### If the amendment is ratified (expected path)

1. Phase 1 → Phase 2 → Phase 3.
2. Phase 4 (US1) → **STOP and validate** against quickstart scenario 1. This is the MVP and the
   feature's headline outcome.
3. Phase 5 (US2), Phase 6 (US3), Phase 7 (US4) in any order — validate each independently.
4. Phase 8.

### If the amendment stalls

1. Phase 1 → Phase 2 → **Phase 6 (US3)** → the latency parts of Phase 8 (T052 only — T060
   already documented the configuration surface back in Phase 2, so nothing in T053 is
   latency-specific after the C1 remediation).
2. Ship latency telemetry as a standalone increment within the unreleased 1.0.0, or hold it; either way the URI and
   operation work resumes at Phase 3 the moment T003 clears.

Do not work around the gate by implementing the fields behind a flag — Principle VII governs what
the starter *may emit*, and a flag does not change what the code is capable of emitting.

---

## Notes

- Every test task must be **observed to fail** before its implementation task; Principle V is
  enforced by the reviewer against commit order, not by good intentions
- `[P]` = different files, no dependency on an incomplete task
- Commit after each task or tight logical group, keeping test commits ahead of implementation commits
- The Non-Intrusion suite (T049, T050) and the auto-config coverage check (T055) are **merge gates** —
  a single failure or skip blocks the PR
- No new dependency may appear in `pom.xml` as a result of this feature; the servlet and WebClient
  attribute keys are deliberately literal strings for exactly this reason (Principle II)

**Total tasks**: 62 — Setup 4, Foundational 6, Gate 3, US1 16, US2 9, US3 7, US4 6, Polish 11.

T058, T059, T060 and T061 are numbered out of sequence deliberately: appending IDs avoids renumbering
T009–T057 and every reference to them in the dependency graph, the same-file-contention list and
the parallel-execution examples. Each carries a note saying where in the order it actually runs.
