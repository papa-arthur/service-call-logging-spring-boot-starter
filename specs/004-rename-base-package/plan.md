# Implementation Plan: Rename Base Package to com.telecelghana.play.app.common

**Branch**: `004-rename-base-package` | **Date**: 2026-09-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-rename-base-package/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Rename every occurrence of the starter's Java package root and Maven `groupId` from `com.bookit`
to `com.telecelghana.play.app.common` (e.g. `com.bookit.servicecalllogging.*` becomes
`com.telecelghana.play.app.common.servicecalllogging.*`), across main source, test source, build
configuration, Spring auto-configuration registration metadata, and living documentation (README).
This is a pure mechanical rename with no behavioral change: no class, method, property key, or
configuration key simple name changes, and no backward-compatible alias/shim classes are retained
in the old package (justified by the starter's pre-1.0.0-publication status). Historical spec/plan/
task documents for already-shipped features (001-003) are left referencing the old package as an
accurate historical record. Verification is by running the full existing test suite unmodified
(other than import/package statement updates) and confirming identical pass/fail results and
coverage.

## Technical Context

**Language/Version**: Java (Spring Boot 3.3.13 parent — same as current)

**Primary Dependencies**: Spring Boot autoconfigure, Spring Web/WebFlux (optional, classpath-conditional), Micrometer, SLF4J, Jackson (optional), ArchUnit (test), JaCoCo (build) — unchanged; only the group/package coordinate changes

**Storage**: N/A — no persistence in this starter

**Testing**: Existing JUnit 5 test suite under `src/test/java`, including `ApplicationContextRunner` auto-configuration slice tests and `DataHygieneArchTest`/`UriDataHygieneTest` ArchUnit tests — all MUST continue to pass unmodified in behavior after the rename

**Target Platform**: JVM library (Spring Boot starter jar) consumed by other Spring Boot services

**Project Type**: Single-module Java library (Maven, Spring Boot starter)

**Performance Goals**: N/A — no performance-affecting change; this is a namespace rename only

**Constraints**: Zero observable behavior change (Principle I); no new/removed public simple names (Principle III/IV); 100% auto-configuration matrix coverage MUST be preserved (existing gate, not weakened by the rename)

**Scale/Scope**: 72 in-scope files (measured after implementation): 24 under `src/main/java`, 45 under `src/test/java`, plus `pom.xml`, `README.md`, and the `AutoConfiguration.imports` registration file. Historical `specs/001-*` and `specs/002-*` planning records are out of scope per spec's Assumptions, with the narrow FR-008 exception for the two documents README links as live consumer instructions

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Applicability to this rename | Status |
|-----------|-------------------------------|--------|
| I. Non-Intrusion (Do No Harm) | No instrumentation logic changes; only package/group coordinates change. The existing Non-Intrusion regression suite is preserved (just recompiled under the new package) and MUST stay green. | PASS |
| II. Zero Forced Footprint | No new dependency is introduced; classpath-conditional activation logic is unchanged, only its enclosing package name changes. | PASS |
| III. Auto-Configured, Fully Overridable | Auto-configuration class names change (FQCN), but the `AutoConfiguration.imports` file is updated in the same change so Spring Boot still discovers it; `@ConditionalOnMissingBean` behavior, the `service-call-logging.enabled` switch, and override mechanics are untouched. | PASS |
| IV. Backward Compatibility & Semantic Versioning | The rename changes public FQCNs and the Maven `groupId` — this IS a breaking change to the public surface (import paths and dependency coordinate). Per this principle, a breaking change MUST increment the MAJOR version and ship a migration note. The starter has not yet published `1.0.0` (per project memory: the binary-compatibility gate is "legitimately skipped while 1.0.0 is unpublished"), so there is no prior public release whose consumers must be protected — the rename can land as part of the still-unpublished `1.0.0`, with a migration note included as forward-looking documentation rather than a MAJOR bump over a real prior release. **Delivered**: README's "Base package renamed" table, which also covers the names that changed as a side effect (logger categories, the `@ConfigurationProperties`/actuator bean name, and string-form auto-configuration exclusions — each of which fails *silently* rather than loudly). | PASS (justified — see Complexity Tracking) |
| V. Test-First (TDD) | No new behavior is introduced, so no new failing-test-first cycle is required for functional logic. The rename itself is verified by re-running the existing (unmodified-assertion) suite and confirming identical results — that suite already exists and already passed under the old package. | PASS |
| VI. Graceful Degradation & Bounded Cost | Degradation paths and cost budgets are implementation-internal and untouched by a namespace rename. | PASS |
| VII. Data Hygiene & Security | The fixed logged-field set and its ArchUnit enforcement (`DataHygieneArchTest`) are unchanged in content; the test class itself simply moves to the new package. | PASS |
| VIII. Documentation as a Deliverable | README MUST be updated in the same change (FR-002) to reflect the new `groupId`/package in the dependency snippet and any FQCN examples (e.g., `OverheadBenchmark`). | PASS |
| IX. Spec-Driven Traceability | This plan traces to `specs/004-rename-base-package/spec.md`, which contains no implementation details beyond the literal old/new package strings that are the subject of the change itself. | PASS |
| X. Simplicity / YAGNI | The simplest mechanism — a straight find-and-replace of the package prefix — is used; no compatibility-shim classes, no dual-package support, no new abstractions are introduced (per spec FR-007). | PASS |

No unjustified violations. See Complexity Tracking for the one principle (IV) that required an
explicit justification rather than a plain pass.

## Project Structure

### Documentation (this feature)

```text
specs/004-rename-base-package/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
pom.xml                                   # TWO sites: <groupId> AND the JaCoCo coverage-gate <include>

src/main/java/com/bookit/                 # -> src/main/java/com/telecelghana/play/app/common/
└── servicecalllogging/
    ├── autoconfigure/
    ├── extractor/
    ├── filter/
    ├── interceptor/
    ├── logging/
    ├── metrics/
    ├── operation/
    ├── resolver/
    └── uri/

src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports   # FQCN line updated

src/test/java/com/bookit/                 # -> src/test/java/com/telecelghana/play/app/common/
└── servicecalllogging/                   #    (mirrors main-source package tree, incl. security/, testsupport/)

README.md                                 # groupId snippet + OverheadBenchmark FQCN example updated
```

**Structure Decision**: Single-module Maven library (Option 1, simplified — no `tests/` top-level
split; this project keeps `src/test/java` mirroring `src/main/java`, which is the existing
convention). The change is a directory/package move-and-rewrite under the existing tree: every
directory currently rooted at `src/{main,test}/java/com/bookit/` moves to
`src/{main,test}/java/com/telecelghana/play/app/common/`, preserving the `servicecalllogging.*`
subtree and all file names exactly. No new top-level directories are introduced.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| Principle IV (Backward Compatibility & Semantic Versioning): the rename breaks any external code compiled against `com.bookit.*` or depending on `groupId com.bookit`, without a deprecation period or compatibility shim. | The requester explicitly asked for a straight rename with no functionality change, and the starter's own `pom.xml` is still at the pre-release `1.0.0` version that project memory records as not yet published (the binary-compatibility/japicmp gate is "legitimately skipped while 1.0.0 is unpublished"). There is therefore no real external consumer on a prior published version whose upgrade path this would break today. | A deprecation shim (keeping `com.bookit.*` classes as deprecated subclasses/re-exports forwarding to the new package) was rejected: it would violate Principle X (Simplicity/YAGNI) by adding dual-package surface area with no real consumer to protect, and it would add new classes/behavior the user explicitly did not ask for ("should not change the core functionality"). If the starter is later confirmed to have external consumers on a published `com.bookit` coordinate, this decision should be revisited and the rename re-planned as a MAJOR release with a migration note instead. |

## Post-Design Constitution Check

*Re-run after Phase 1 design (data-model.md, contracts/, quickstart.md). Must pass before `/speckit-tasks`.*

Phase 1 design did not introduce any new component, abstraction, dependency, or behavior beyond
what Phase 0/Constitution Check already accounted for — it only made the rename's scope and
mapping explicit (data-model.md's artifact inventory, contracts/public-surface-rename.md's FQCN
table, quickstart.md's verification steps). Re-checking each principle against that design:

| Principle | Post-design status | Notes |
|-----------|--------------------|-------|
| I. Non-Intrusion | PASS | quickstart.md Step 4 explicitly re-runs the Non-Intrusion regression suite as part of the full test suite; no design element touches instrumentation logic. |
| II. Zero Forced Footprint | PASS | No dependency changes anywhere in the design artifacts. |
| III. Auto-Configured, Fully Overridable | PASS | contracts/public-surface-rename.md flags the `AutoConfiguration.imports` registration file as the single highest-risk artifact and requires it be updated atomically with the class move (data-model.md, Relationships section). |
| IV. Backward Compatibility & Semantic Versioning | PASS (justified) | Unchanged from pre-design — see Complexity Tracking above; contracts/public-surface-rename.md's "Compatibility Statement" makes the no-shim decision explicit and auditable. |
| V. Test-First (TDD) | PASS | No new behavior requires a new red-green cycle; quickstart.md's baseline-vs-post-rename comparison (Steps 1 and 4) is the appropriate verification method for a pure rename. |
| VI. Graceful Degradation & Bounded Cost | PASS | Not touched by any design artifact. |
| VII. Data Hygiene & Security | PASS | data-model.md confirms `DataHygieneArchTest` and its permitted-field enforcement move package only; content is unchanged. |
| VIII. Documentation as a Deliverable | PASS | data-model.md category 5 and quickstart.md Step 6 both require and verify the README update. |
| IX. Spec-Driven Traceability | PASS | This plan and its Phase 0/1 artifacts trace to spec.md; no implementation detail beyond the literal package/groupId strings (which are the subject of the change) appears in the spec. |
| X. Simplicity / YAGNI | PASS | contracts/public-surface-rename.md's Compatibility Statement confirms no shim/alias classes were designed in; data-model.md's transformation rules are the minimal move-and-rewrite, nothing more. |

**Result**: All ten principles pass post-design, with Principle IV's pass remaining justified (not
plain) per the Complexity Tracking entry above. Ready for `/speckit-tasks`.
