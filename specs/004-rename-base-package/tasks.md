---
description: "Task list template for feature implementation"
---

# Tasks: Rename Base Package to com.telecelghana.play.app.common

**Input**: Design documents from `/specs/004-rename-base-package/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Tests**: No new behavior is introduced, so no new failing-test-first tasks are generated. Verification is by re-running the existing suite unmodified and comparing to a pre-rename baseline (per research.md §4 and quickstart.md).

**Organization**: This feature is a single atomic rename — nothing compiles until package, `groupId`, and auto-configuration registration are all renamed together (see plan.md's Constitution Check, Principle III note). The mechanical rename itself is therefore the **Foundational** phase; each user story phase is a verification/demonstration pass layered on top of the completed rename, matching spec.md's three independently-testable acceptance angles.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Old package root: `com.bookit` — New package root: `com.telecelghana.play.app.common`
- Old `pom.xml` groupId: `com.bookit` — New: `com.telecelghana.play.app.common`

## Path Conventions

Single Maven project. Main source under `src/main/java/com/bookit/servicecalllogging/**` moves to
`src/main/java/com/telecelghana/play/app/common/servicecalllogging/**`; test source moves the same
way under `src/test/java/**`. See `data-model.md` for the full artifact-category inventory and
`contracts/public-surface-rename.md` for the authoritative FQCN mapping.

---

## Phase 1: Setup

**Purpose**: Capture a pre-rename baseline to compare against after the rename (SC-002).

- [X] T001 Run `mvn -q clean verify` at the repository root and record: total test count, pass count, and the JaCoCo coverage percentage for the `com.bookit.servicecalllogging.autoconfigure` package. Save these numbers (e.g. in a scratch note) for comparison in T031.

**Checkpoint**: Baseline captured — safe to begin the rename.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Perform the actual package/groupId rename across main source, test source, build
configuration, and auto-configuration registration. **The project will not compile until this
entire phase is complete** — Java does not allow a half-renamed package tree to build, so every
task in this phase must land together before any user story can be verified.

**⚠️ CRITICAL**: No user story verification can begin until this phase is complete and `mvn clean verify` succeeds (T027).

- [X] T002 [P] Update `pom.xml`: change `<groupId>com.bookit</groupId>` to `<groupId>com.telecelghana.play.app.common</groupId>` (leave `<artifactId>` and `<version>` unchanged). **Amended during implementation**: `pom.xml` contained a *second* package reference this task originally missed — the JaCoCo `auto-configuration-matrix-gate` rule's `<include>com.bookit.servicecalllogging.autoconfigure</include>` (line ~261). It was renamed too. Had it been left, the constitution's Auto-Configuration Matrix Coverage Gate would have matched no package and passed vacuously — reporting success while measuring nothing.
- [X] T003 [P] Move and rewrite the 4 root-level main classes from `src/main/java/com/bookit/servicecalllogging/` to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/`, updating each file's `package` statement: `EnvelopeFieldExtractor.java`, `EnvelopeMatch.java`, `ResponseCodeExtractor.java`, `ServiceCallLoggingProperties.java`. Update any `import com.bookit....` statements within these files to the new package.
- [X] T004 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/autoconfigure/` (`RestTemplateInstrumentationConfiguration.java`, `ServiceCallLoggingAutoConfiguration.java`, `WebClientInstrumentationConfiguration.java`) to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/autoconfigure/`, updating `package`/`import` statements.
- [X] T005 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/extractor/` (`JacksonEnvelopeFieldExtractor.java`, `JacksonResponseCodeExtractor.java`) to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/extractor/`, updating `package`/`import` statements.
- [X] T006 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/filter/OutboundCallExchangeFilter.java` to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/filter/OutboundCallExchangeFilter.java`, updating `package`/`import` statements.
- [X] T007 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/interceptor/` (`BufferingClientHttpResponse.java`, `HttpStatusGroup.java`, `OutboundCallInterceptor.java`) to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/interceptor/`, updating `package`/`import` statements.
- [X] T008 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/logging/` (`CallLogger.java`, `OutboundCallRecord.java`) to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/logging/`, updating `package`/`import` statements.
- [X] T009 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/metrics/` (`OutboundCallMetrics.java`, `Outcome.java`, `ResponseCodeResult.java`) to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/metrics/`, updating `package`/`import` statements.
- [X] T010 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/operation/OperationResolver.java` to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/operation/OperationResolver.java`, updating `package`/`import` statements.
- [X] T011 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/resolver/DestinationNameResolver.java` to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/resolver/DestinationNameResolver.java`, updating `package`/`import` statements.
- [X] T012 [P] Move and rewrite `src/main/java/com/bookit/servicecalllogging/uri/` (`CapturingUriTemplateHandler.java`, `DestinationUriResolver.java`, `InboundUriResolver.java`, `UriTemplateCapture.java`) to `src/main/java/com/telecelghana/play/app/common/servicecalllogging/uri/`, updating `package`/`import` statements.
- [X] T013 Update `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: change the listed FQCN from `com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration` to `com.telecelghana.play.app.common.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration` (depends on T004 — the class must already exist at its new FQCN).
- [X] T014 Run `mvn -q compile` at the repository root and fix any remaining old-package `import`/reference in main source until it compiles cleanly (depends on T002–T013).
- [X] T015 [P] Move and rewrite the 2 root-level test classes from `src/test/java/com/bookit/servicecalllogging/` to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/`: `OverheadBenchmark.java`, `ServiceCallLoggingPropertiesTest.java`. Update `package`/`import` statements.
- [X] T016 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/autoconfigure/` (`CustomHeaderNamesTest.java`, `DestinationResolverOverrideTest.java`, `DisabledStarterTest.java`, `JacksonAbsentFallbackTest.java`, `MetricsAbsentWhenNoRegistryTest.java`, `MetricsAutoConfigurationOrderingTest.java`, `ResponseCodeExtractorOverrideTest.java`, `RestTemplateConfigurationActivationTest.java`, `ServiceCallLoggingAutoConfigurationTest.java`, `SourceNameWarningTest.java`, `ValidationProviderAbsentTest.java`, `WebClientConfigurationActivationTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/autoconfigure/`, updating `package`/`import` statements and any FQCN string literals used in assertions.
- [X] T017 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/extractor/` (`JacksonEnvelopeFieldExtractorTest.java`, `JacksonResponseCodeExtractorTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/extractor/`, updating `package`/`import` statements.
- [X] T018 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/filter/` (`NonIntrusionWebClientTest.java`, `OutboundCallExchangeFilterTest.java`, `WebClientIntegrationTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/filter/`, updating `package`/`import` statements.
- [X] T019 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/interceptor/` (`BufferingClientHttpResponseTest.java`, `NonIntrusionRestTemplateTest.java`, `OutboundCallInterceptorTest.java`, `RestTemplateIntegrationTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/interceptor/`, updating `package`/`import` statements.
- [X] T020 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/logging/` (`CallLoggerTest.java`, `DataHygieneTest.java`, `OutboundCallRecordTest.java`, `TwoLogEntriesTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/logging/`, updating `package`/`import` statements.
- [X] T021 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/metrics/` (`LatencyDistributionTest.java`, `MetricsIntegrationTest.java`, `OperationCardinalityTest.java`, `OutboundCallMetricsTest.java`, `OutcomeTest.java`, `ResponseCodeResultTest.java`, `UriCardinalityTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/metrics/`, updating `package`/`import` statements.
- [X] T022 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/operation/OperationResolverTest.java` to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/operation/OperationResolverTest.java`, updating `package`/`import` statements.
- [X] T023 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/resolver/DestinationNameResolverTest.java` to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/resolver/DestinationNameResolverTest.java`, updating `package`/`import` statements.
- [X] T024 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/security/` (`DataHygieneArchTest.java`, `UriDataHygieneTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/security/`, updating `package`/`import` statements. **Additionally** update the `BASE_PACKAGE` string constant in `DataHygieneArchTest.java` (currently `"com.bookit.servicecalllogging"`) and the fully-qualified `com.bookit.servicecalllogging.logging.OutboundCallRecord.class` reference to the new package — these are literal strings/references ArchUnit uses to scan the codebase and will not be caught by a simple `import` rewrite.
- [X] T025 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/testsupport/` (`FakeClientHttpResponse.java`, `RecordingCallLogger.java`, `StubHttpServer.java`, `TestProperties.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/testsupport/`, updating `package`/`import` statements.
- [X] T026 [P] Move and rewrite `src/test/java/com/bookit/servicecalllogging/uri/` (`DestinationUriResolverTest.java`, `InboundUriResolverTest.java`, `UriTemplateCaptureTest.java`) to `src/test/java/com/telecelghana/play/app/common/servicecalllogging/uri/`, updating `package`/`import` statements.
- [X] T027 Run `mvn -q clean verify` at the repository root and fix any remaining old-package reference until the full build (compile + test + coverage) succeeds cleanly (depends on T002–T026).

**Checkpoint**: Foundation renamed and green — all three user story verifications below can now proceed (in any order).

---

## Phase 3: User Story 1 - Consuming team upgrades to the renamed package (Priority: P1) 🎯 MVP

**Goal**: Demonstrate that a consuming team can rebuild against the renamed starter with zero functional difference.

**Independent Test**: Package the renamed starter and confirm its auto-configuration activates identically to before, with the new coordinate/imports resolving cleanly.

### Verification for User Story 1

- [X] T028 [US1] Run `mvn -q -Dtest=ServiceCallLoggingAutoConfigurationTest,WebClientConfigurationActivationTest,RestTemplateConfigurationActivationTest test` and confirm all three pass, demonstrating the auto-configuration is discovered and activated under its new FQCN with no missing-bean, duplicate-bean, or misordered-configuration errors (spec Acceptance Scenario US1-2).
- [X] T029 [US1] Run `mvn -q package` then `jar tf target/service-call-logging-spring-boot-starter-1.0.0.jar | grep telecelghana` to confirm the built jar's class entries live under `com/telecelghana/play/app/common/servicecalllogging/**`, and run `mvn -q help:evaluate -Dexpression=project.groupId -DforceStdout` to confirm it prints `com.telecelghana.play.app.common` — demonstrating a consuming team's updated dependency coordinate and imports resolve to a correctly-built artifact (spec Acceptance Scenario US1-1).

**Checkpoint**: User Story 1 independently verified — the migration path works.

---

## Phase 4: User Story 2 - Maintainer verifies no functional regression after the rename (Priority: P2)

**Goal**: Confirm the rename introduced zero behavioral change relative to the Phase 1 baseline.

**Independent Test**: Compare full test-suite results and coverage before and after the rename.

### Verification for User Story 2

- [X] T030 [US2] Run `mvn -q clean verify` (post-rename) and record total test count, pass count, and the JaCoCo coverage percentage for the `com.telecelghana.play.app.common.servicecalllogging.autoconfigure` package.
- [X] T031 [US2] Compare T030's results against the T001 baseline: confirm the same total test count and pass count (only namespaces differ), and confirm the auto-configuration coverage percentage is unchanged (100%, per the existing Auto-Configuration Matrix Coverage Gate). Record the comparison as the completion evidence for SC-002.

**Checkpoint**: User Story 2 independently verified — no regression introduced.

---

## Phase 5: User Story 3 - Reader of project documentation sees a consistent namespace (Priority: P3)

**Goal**: Ensure living documentation reflects only the new package/coordinate.

**Independent Test**: Search README.md for the old package string and confirm zero matches.

### Implementation for User Story 3

- [X] T032 [US3] Update `README.md`: change the dependency snippet's `<groupId>com.bookit</groupId>` to `<groupId>com.telecelghana.play.app.common</groupId>`, and change the `com.bookit.servicecalllogging.OverheadBenchmark` FQCN example to `com.telecelghana.play.app.common.servicecalllogging.OverheadBenchmark`.
- [X] T033 [US3] Run `grep -n "com\.bookit" README.md` and confirm no output.

**Checkpoint**: All three user stories independently verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Repository-wide confirmation that the rename is complete and consistent.

- [X] T034 Run `grep -rIn "com[./]bookit" . --exclude-dir=target --exclude-dir=.git --exclude-dir=specs --exclude=README.md` from the repository root and confirm no output, then run the README-specific check below — SC-001. **Corrected during implementation**, twice: (1) the original command excluded `specs/001-…` and `specs/002-…` by *path*, but `--exclude-dir` matches a directory *name*, so those exclusions silently did nothing, and it also failed to exclude `specs/004-…`, whose own documents necessarily quote the old name — as written it could never return empty and would have reported a false failure on a perfect rename; (2) README.md now carries the migration note required by Principle IV, which must quote the old package to tell consumers what to change *from*. A blanket search cannot tell a stale reference from deliberate before→after documentation, so the README is checked with the section-aware command in T034b instead.
- [X] T034b Confirm every remaining old-package mention in `README.md` sits inside the migration note and none is a stale live reference: `awk '/^### Base package renamed/{skip=1} /^## /{skip=0} !skip' README.md | grep -nE "com\.bookit|\bc\.b\."` — expect no output.
- [X] T034a Run the abbreviated-form sweep `grep -rnE '\bc\.b\.' . --exclude-dir=target --exclude-dir=.git --exclude-dir=specs` and confirm no output. **Added during implementation**: Logback's `%logger{n}` renders the old package as `c.b.s.`, which contains no `com.bookit` substring, so the literal grep in T034 structurally cannot see it. Two such stale samples were found in README.md (lines 53, 56) and fixed.
- [X] T035 Execute `quickstart.md` end-to-end (Steps 1–6) and confirm every step's expected outcome is met, as the final sign-off that SC-001 through SC-004 are all satisfied.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — run first to capture the baseline.
- **Foundational (Phase 2)**: Depends on Setup (T001) only for the baseline numbers it will later be compared against; the rename work itself (T002–T026) has no dependency on T001 and could start immediately, but T027 (the phase-closing full verify) is what all user stories key off. **BLOCKS all user stories.**
- **User Stories (Phase 3-5)**: All depend on Phase 2 completing (T027 green). Once T027 passes, US1, US2, and US3 can proceed in any order or in parallel — none depends on another.
- **Polish (Phase 6)**: Depends on all three user story phases being complete.

### Within Foundational (Phase 2)

- T002–T012, T015–T026: all independent file-move-and-rewrite tasks, safe to run in parallel `[P]`.
- T013 depends on T004 (the autoconfigure class must exist at its new FQCN before the registration file can name it).
- T014 depends on T002–T013 (main source + build file + registration must all be in place to compile).
- T027 depends on everything in Phase 2 (full build + test + coverage).

### Within Each User Story

- No internal ordering constraints — each story's tasks are independent verification steps that can run in any order or in parallel where marked `[P]` (none are, since each user story's tasks build on the shared Phase 2 completion rather than on each other).

---

## Parallel Example: Foundational Phase

```bash
# Launch all independent main-source package moves together:
Task: "Move and rewrite root-level main classes (T003)"
Task: "Move and rewrite autoconfigure main package (T004)"
Task: "Move and rewrite extractor main package (T005)"
Task: "Move and rewrite filter main package (T006)"
Task: "Move and rewrite interceptor main package (T007)"
Task: "Move and rewrite logging main package (T008)"
Task: "Move and rewrite metrics main package (T009)"
Task: "Move and rewrite operation main package (T010)"
Task: "Move and rewrite resolver main package (T011)"
Task: "Move and rewrite uri main package (T012)"

# Then, once main source is stable, launch all independent test-source package moves together:
Task: "Move and rewrite root-level test classes (T015)"
Task: "Move and rewrite autoconfigure test package (T016)"
Task: "Move and rewrite extractor test package (T017)"
Task: "Move and rewrite filter test package (T018)"
Task: "Move and rewrite interceptor test package (T019)"
Task: "Move and rewrite logging test package (T020)"
Task: "Move and rewrite metrics test package (T021)"
Task: "Move and rewrite operation test package (T022)"
Task: "Move and rewrite resolver test package (T023)"
Task: "Move and rewrite security test package (T024)"
Task: "Move and rewrite testsupport test package (T025)"
Task: "Move and rewrite uri test package (T026)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (capture baseline).
2. Complete Phase 2: Foundational (the entire rename — CRITICAL, blocks all stories).
3. Complete Phase 3: User Story 1 (consumer migration path verified).
4. **STOP and VALIDATE**: User Story 1's two checks (T028, T029) pass.
5. This alone already delivers a working, correctly-renamed, publishable starter.

### Incremental Delivery

1. Setup + Foundational → the rename itself is done and green.
2. Add User Story 1 → migration path verified (MVP proof point).
3. Add User Story 2 → regression-free confirmed against baseline.
4. Add User Story 3 → documentation made consistent.
5. Polish → repository-wide leftover-reference sweep and full quickstart sign-off.

### Parallel Team Strategy

With multiple contributors: one person can drive the Foundational move-and-rewrite tasks (or split
the `[P]` tasks across contributors, since each touches a distinct directory), then once T027 is
green, three people can pick up US1/US2/US3 verification simultaneously — none depends on another.

---

## Notes

- This feature has no data entities and no network-facing contracts in the usual sense; the
  "contract" is the public FQCN/coordinate mapping in `contracts/public-surface-rename.md`, which
  every Foundational task must honor exactly (simple names unchanged, only the prefix changes).
- No `[NEEDS CLARIFICATION]` markers remain anywhere in this feature's artifacts.
- Historical spec documents (`specs/001-outbound-http-observability/`,
  `specs/002-configurable-envelope-fields/`) are explicitly out of scope (FR-008) and MUST NOT be
  touched by any task above.
- Commit after Phase 2 completes (T027 green) and again after each user story phase, so the
  rename's completion and each verification pass are independently reviewable.
