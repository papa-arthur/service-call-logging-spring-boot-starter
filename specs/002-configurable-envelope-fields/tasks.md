---

description: "Task list for Configurable Response Envelope Field Names"
---

# Tasks: Configurable Response Envelope Field Names

**Input**: Design documents from `specs/002-configurable-envelope-fields/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md (all present)
**Constitution gate**: Principle VII (Data Hygiene) was amended to v1.1.0 to permit the new
`responseMessage` field — already ratified, not a pending blocker for this task list.

**Tests**: Included. The project constitution's Principle V (Test-First, NON-NEGOTIABLE) requires
a failing test before every implementation commit, so every task below is either a test task or
is sequenced after the test task(s) it makes pass.

**Organization**: Tasks are grouped by user story. **Note on this feature specifically**: unlike a
typical feature, this one is a single configurable matching engine (`EnvelopeFieldExtractor`) —
User Stories 1–4 are different *configuration scenarios* exercised against that one engine, not
separable subsystems. Foundational therefore carries essentially all production code; each user
story phase after it contributes the dedicated integration-level proof that its specific FR
subset holds, not new production capability. This is called out again in Implementation Strategy
below so it isn't mistaken for stories being "unimplemented."

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1/US2/US3/US4, per spec.md's priorities (P1–P4)
- File paths are exact, relative to the repository root

---

## Phase 1: Setup

- [X] T001 Run `mvn test` at the repository root and confirm the existing 154-test baseline
      passes cleanly before any change (establishes the regression baseline this feature must
      not break — SC-003)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The matching engine and every plumbing change every user story depends on.

**⚠️ CRITICAL**: No user story phase can be verified until this phase is complete.

### Tests for Foundational (write first; each MUST fail before its implementation task lands)

- [X] T002 [P] Extend `src/test/java/com/bookit/servicecalllogging/ServiceCallLoggingPropertiesTest.java` — assert `envelopes` defaults to an empty list, and that a partially-specified entry (e.g. only `code-field` set) binds its other fields to their documented defaults via Spring's relaxed binder
- [X] T003 [P] Create `src/test/java/com/bookit/servicecalllogging/extractor/EnvelopeFieldExtractorTest.java` — ordered-list matching (first code-field-present-and-numeric wins), per-field fallback, built-in-default fallback when nothing matches, ambiguous-body-earlier-entry-wins ordering, non-string message / non-numeric code handling, never throws on malformed input (`contracts/envelope-matching.md`)
- [X] T004 [P] Extend `src/test/java/com/bookit/servicecalllogging/metrics/ResponseCodeResultTest.java` — assert `ResponseCodeResult.of(rawCode, successfulValue)` classifies `SUCCESS` only when `rawCode == successfulValue`, `FAILURE` otherwise
- [X] T005 [P] Extend `src/test/java/com/bookit/servicecalllogging/logging/OutboundCallRecordTest.java` — assert the new `message` field is carried and nullable, independently of `responseCode`
- [X] T006 [P] Extend `src/test/java/com/bookit/servicecalllogging/logging/CallLoggerTest.java` — assert the log line includes `responseMessage=<value>` when present or `responseMessage=absent` when not
- [X] T007 [P] Extend `src/test/java/com/bookit/servicecalllogging/extractor/JacksonResponseCodeExtractorTest.java` — assert the default extractor's code now comes from `EnvelopeFieldExtractor`'s match, and that all existing empty/non-JSON/absent-field cases still pass unchanged
- [X] T008 [P] Extend `src/test/java/com/bookit/servicecalllogging/interceptor/BufferingClientHttpResponseTest.java` — assert the prefix bytes `peek()` already read are exposed for a second, independent extraction call with no additional body read
- [X] T009 [P] Extend `src/test/java/com/bookit/servicecalllogging/security/DataHygieneArchTest.java` — permit the new `message`/`responseMessage` field per the ratified constitution v1.1.0, while still rejecting every other field name (credentials, cookies, headers, bodies)
- [X] T010 [P] Extend `src/test/java/com/bookit/servicecalllogging/metrics/OutboundCallMetricsTest.java` — add a negative assertion that no message-derived tag is ever registered on the counter (guards `research.md` §5)
- [X] T011 [P] Extend `src/test/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfigurationTest.java` — add `EnvelopeFieldExtractor` to the activation matrix (Jackson present/absent × consumer-override present/absent), mirroring the existing `JacksonResponseCodeExtractor` rows
- [X] T012 [P] Extend `src/test/java/com/bookit/servicecalllogging/autoconfigure/ResponseCodeExtractorOverrideTest.java` — assert a consumer-supplied `ResponseCodeExtractor` still supplies the code exactly as today, while `responseMessage` is still populated independently (FR-016)
- [X] T013 [P] Extend `src/test/java/com/bookit/servicecalllogging/interceptor/NonIntrusionRestTemplateTest.java` — add: an `EnvelopeFieldExtractor` failure degrades to `responseMessage=absent` without affecting the call or `responseCode`; a malformed `envelopes` entry does not prevent startup or instrumentation
- [X] T014 [P] Extend `src/test/java/com/bookit/servicecalllogging/filter/NonIntrusionWebClientTest.java` — the same two cases as T013, reactive path

### Implementation for Foundational (in dependency order; makes T002–T014 pass)

- [X] T015 Add the `Envelope` nested record (`codeField` `@DefaultValue "responseCode"`, `messageField` `@DefaultValue "message"`, `successfulValue` `@DefaultValue "0"`) and the `envelopes` `List<Envelope>` field to `src/main/java/com/bookit/servicecalllogging/ServiceCallLoggingProperties.java` (makes T002 pass)
- [X] T016 [P] Create `src/main/java/com/bookit/servicecalllogging/extractor/EnvelopeMatch.java` — record `{Integer rawCode, int successfulValue, String message}`, all per `data-model.md`
- [X] T017 Create `src/main/java/com/bookit/servicecalllogging/extractor/EnvelopeFieldExtractor.java` — the matching engine per `contracts/envelope-matching.md`: parse once, walk the configured `Envelope` list then the built-in default, first code-field match wins, never throws (depends on T015, T016; makes T003 pass)
- [X] T018 [P] Change `ResponseCodeResult.of(int rawCode)` to `of(int rawCode, int successfulValue)` in `src/main/java/com/bookit/servicecalllogging/metrics/ResponseCodeResult.java` (makes T004 pass)
- [X] T019 [P] Add the `message` field to `src/main/java/com/bookit/servicecalllogging/logging/OutboundCallRecord.java` (makes T005 pass)
- [X] T020 Update `src/main/java/com/bookit/servicecalllogging/logging/CallLogger.java` to log `responseMessage={}` (depends on T019; makes T006 pass)
- [X] T021 Update `src/main/java/com/bookit/servicecalllogging/extractor/JacksonResponseCodeExtractor.java` to delegate to `EnvelopeFieldExtractor` — `extract(bytes)` returns `Optional.ofNullable(match.rawCode())` — public SPI shape unchanged (depends on T017; makes T007 pass)
- [X] T022 [P] Add a cached-prefix-bytes accessor to `src/main/java/com/bookit/servicecalllogging/interceptor/BufferingClientHttpResponse.java`, reusable after `peek()` has run (makes T008 pass)
- [X] T023 Wire the `EnvelopeFieldExtractor` `@ConditionalOnMissingBean`/`@ConditionalOnClass(Jackson)` bean and update the `jacksonResponseCodeExtractor` bean method's parameters in `src/main/java/com/bookit/servicecalllogging/autoconfigure/ServiceCallLoggingAutoConfiguration.java` (depends on T017, T021; makes T011 pass)
- [X] T024 [P] Inject `ObjectProvider<EnvelopeFieldExtractor>` into the `OutboundCallInterceptor` bean construction in `src/main/java/com/bookit/servicecalllogging/autoconfigure/RestTemplateInstrumentationConfiguration.java` (depends on T023)
- [X] T025 [P] Inject `ObjectProvider<EnvelopeFieldExtractor>` into the `OutboundCallExchangeFilter` bean construction in `src/main/java/com/bookit/servicecalllogging/autoconfigure/WebClientInstrumentationConfiguration.java` (depends on T023)
- [X] T026 [P] Update `src/main/java/com/bookit/servicecalllogging/interceptor/OutboundCallInterceptor.java` to call `EnvelopeFieldExtractor` on the bytes `BufferingClientHttpResponse` already cached (T022), classify via `ResponseCodeResult.of(rawCode, match.successfulValue())` (T018), and build `OutboundCallRecord` with the message (T019) (depends on T018, T019, T022, T024; makes T009, T010, T012, T013 pass for the blocking path)
- [X] T027 [P] Update `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java` to mirror T026 on the reactive path, reusing its existing `BoundedBodyPrefix.bytes()` (depends on T018, T019, T025; makes T009, T010, T012, T014 pass for the reactive path)

**Checkpoint**: `mvn test` passes. Every call — configured or not — is classified using a
configurable successful value and logs `responseMessage`; a service with nothing configured is
byte-for-byte unchanged from before this feature (FR-015).

---

## Phase 3: User Story 1 - Configure a Custom Field-Name Pair (Priority: P1) 🎯 MVP

**Goal**: An adopter whose downstream API uses different field names for the same code/message
pair (e.g. `statusCode`/`message`) can configure them via properties instead of the built-in
`responseCode`/`message` defaults.

**Independent Test**: Configure a custom code/message field-name combination for one destination,
call a stub returning its outcome under those names, and confirm the log/metrics reflect the
configured names — while a call to an unconfigured destination still uses the defaults.

- [X] T028 [P] [US1] Add an integration test to `src/test/java/com/bookit/servicecalllogging/interceptor/RestTemplateIntegrationTest.java`: configure a custom `code-field`/`message-field` combination, call a stub returning that shape, assert the log and metrics reflect the configured names; call a second, unconfigured stub and assert it still uses the built-in defaults (spec.md US1, Acceptance Scenarios 1–3)
- [X] T029 [P] [US1] Add the mirrored integration test to `src/test/java/com/bookit/servicecalllogging/filter/WebClientIntegrationTest.java`

**Checkpoint**: User Story 1 independently verified.

---

## Phase 4: User Story 2 - Configure the Successful Outcome Value (Priority: P2)

**Goal**: An adopter whose downstream API's success indicator isn't `0` can configure the correct
value; every other observed code — not only a single previously-assumed failure value — is
classified unsuccessful.

**Independent Test**: Configure a non-default successful value, exercise calls returning that
value, the previously-assumed failure value, and a never-seen value; confirm only the first is
classified successful.

- [X] T030 [P] [US2] Add an integration test to `RestTemplateIntegrationTest.java`: configure a non-default `successful-value`, exercise calls returning that value, `1`, and a third never-seen value, assert classification and that raw values are logged in every case (spec.md US2, Acceptance Scenarios 1–2)
- [X] T031 [P] [US2] Add the mirrored integration test to `WebClientIntegrationTest.java`
- [X] T032 [P] [US2] Extend `src/test/java/com/bookit/servicecalllogging/metrics/MetricsIntegrationTest.java` — assert success/failure counters reflect the configured successful value, not the built-in `0` (spec.md US2, Acceptance Scenario 3)

**Checkpoint**: User Stories 1 and 2 both independently verified.

---

## Phase 5: User Story 3 - Distinct Envelope Conventions for Multiple External APIs (Priority: P3)

**Goal**: A service calling several external APIs, each with its own field-name-and-successful
-value convention, has every call interpreted by the combination that matches its own response
body — with no cross-contamination, and no destination binding required.

**Independent Test**: Configure at least two distinct combinations, exercise calls whose bodies
each match one, plus a call matching neither; confirm no cross-contamination and correct fallback
to the built-in default for the third.

- [X] T033 [P] [US3] Add an integration test to `RestTemplateIntegrationTest.java`: configure two distinct combinations, call stubs matching each plus a third stub matching neither; assert each call is classified/logged using only its own matching combination and the third falls back to the built-in default (spec.md US3, Acceptance Scenarios 1–3)
- [X] T034 [P] [US3] Add the mirrored integration test to `WebClientIntegrationTest.java`
- [X] T035 [US3] Extend the tests added in T033/T034 with a body that could satisfy two configured combinations at once, asserting the earlier entry in configuration order wins deterministically (`contracts/envelope-matching.md`) (depends on T033, T034 — same test methods)
- [X] T036 [P] [US3] Extend `MetricsIntegrationTest.java` — assert per-combination metric correctness across three simultaneous combinations, matching SC-002

**Checkpoint**: User Stories 1, 2, and 3 all independently verified.

---

## Phase 6: User Story 4 - Safe Fallback When Configuration Is Absent, Partial, or Mismatched (Priority: P4)

**Goal**: Misconfiguration or an unexpected response shape never breaks a business call — an
unconfigured service is unchanged, a partial combination falls back per-field, and a body
matching nothing is reported absent, never as an error.

**Independent Test**: Exercise, independently: no configuration at all; a combination missing one
field name; a response body matching no configured or default combination. Confirm every case
completes normally with the correct `absent` logging.

- [X] T037 [P] [US4] Add an integration test to `RestTemplateIntegrationTest.java` covering: no `envelopes` configured at all (byte-for-byte identical logging to pre-feature behaviour); an entry configuring only `code-field` (message falls back to the built-in default name); a response body matching no configured or default combination (`responseCode=absent responseMessage=absent`, no exception, response delivered unmodified) (spec.md US4, Acceptance Scenarios 1–3)
- [X] T038 [P] [US4] Add the mirrored integration test to `WebClientIntegrationTest.java`

**Checkpoint**: All four user stories independently verified. Every FR in spec.md has at least one
passing test.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T039 [P] Add a configured-multi-combination scenario to `src/test/java/com/bookit/servicecalllogging/OverheadBenchmark.java` and re-run it (`mvn test-compile && java -cp ... OverheadBenchmark`); record the new measured per-call overhead figure (Constitution Principle VI; `research.md` §3)
- [X] T040 Update `README.md`: new `envelopes` configuration section (`contracts/configuration.md`), the new `responseMessage` logged field, three new known limitations (order-sensitivity, non-field-based custom extractors, unbounded message logging), and the re-measured overhead figure from T039 (depends on T039)
- [X] T041 Run `specs/002-configurable-envelope-fields/quickstart.md` end-to-end against a running service and confirm every step's expected outcome
- [X] T042 Run `mvn test` (full suite) and confirm 100% pass with zero regressions against the pre-feature 154-test baseline from T001

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS all user stories — this is where the
  matching engine itself is built and unit-tested.
- **User Stories (Phase 3–6)**: All depend on Foundational only. Each phase is independently
  verifiable once Foundational is done; they do not depend on each other despite the numbering
  (US2 does not require US1's test task to have run, etc.) — priority order is a recommendation,
  not a hard dependency.
- **Polish (Phase 7)**: Depends on all four user story phases being complete.

### Within Foundational

Tests (T002–T014) have no ordering dependency on each other. Implementation (T015–T027) must
follow the dependency chain noted inline on each task (property record → extraction engine →
factory/record changes → auto-configuration wiring → interceptor/filter wiring).

### Parallel Opportunities

- All Foundational test tasks (T002–T014) can be written in parallel — 13 different files.
- Within Foundational implementation: T015+T016 in parallel; then T017; T018/T019/T022 in
  parallel with each other and with T017's downstream chain; T024/T025 in parallel once T023
  lands; T026/T027 in parallel once their respective prerequisites land.
- Once Foundational is complete, all four user story phases can proceed in parallel (different
  team members), though T035 must follow T033/T034 (same test files).

---

## Parallel Example: Foundational Tests

```bash
# All of these can be written/launched together — 13 independent files:
Task: "Extend ServiceCallLoggingPropertiesTest.java for envelopes defaults + per-field fallback"
Task: "Create EnvelopeFieldExtractorTest.java for ordered matching + never-throws"
Task: "Extend ResponseCodeResultTest.java for of(rawCode, successfulValue)"
Task: "Extend OutboundCallRecordTest.java for the new message field"
# ...and so on through T014
```

## Parallel Example: User Story 1

```bash
Task: "Integration test for custom field-name combination in RestTemplateIntegrationTest.java"
Task: "Integration test for custom field-name combination in WebClientIntegrationTest.java"
```

---

## Implementation Strategy

### A note specific to this feature

Because `EnvelopeFieldExtractor` is one generic, configuration-driven engine, **completing
Foundational already implements the full behaviour behind User Stories 1–4** — a service could
configure multiple combinations, non-default successful values, and rely on fallback behaviour
immediately after Foundational lands, correctly. What Phases 3–6 add is the *dedicated,
independent proof* that each specific scenario the spec calls out actually holds — required by
this project's TDD-and-traceability discipline (Principles V and IX), not because the capability
is otherwise missing. Do not report User Story 2/3/4 as "not implemented" once Foundational is
done; report them as "implemented, not yet independently verified" until their phase's tasks run.

### MVP First (Foundational + User Story 1)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational) — this is the bulk of the real work.
2. Complete Phase 3 (User Story 1) — the specific, reported pain point (custom field names).
3. **STOP and VALIDATE**: run T028/T029, confirm green, demo against a real non-standard envelope.

### Incremental Delivery

1. Setup + Foundational → engine built, unit-tested, non-intrusion-safe.
2. + User Story 1 → demo-able custom field names (MVP).
3. + User Story 2 → demo-able custom successful value.
4. + User Story 3 → demo-able multi-API support.
5. + User Story 4 → fallback/degradation guarantees independently proven.
6. + Polish → README, benchmark figure, quickstart validated, full suite green.
