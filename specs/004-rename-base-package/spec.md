# Feature Specification: Rename Base Package to com.telecelghana.play.app.common

**Feature Branch**: `004-rename-base-package`

**Created**: 2026-09-04

**Status**: Draft

**Input**: User description: "change the package from com.bookit to com.telecelghana.play.app.common update every use/reference of it int the starter. This should not change the core functionality of the starter"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consuming team upgrades to the renamed package (Priority: P1)

A development team that already depends on the starter upgrades to the new release, updates its dependency coordinates and import statements to the new namespace, and rebuilds its application without any change in the starter's observed behavior (same logging, metrics, headers, and auto-configuration outcomes as before).

**Why this priority**: This is the core value of the change — a consuming team needs a predictable, purely mechanical migration path to the new organizational namespace, with zero functional risk to their running application.

**Independent Test**: Can be fully tested by building a sample consumer application against the newly renamed starter (updated imports and dependency coordinate) and confirming the existing outbound-call logging/metrics behavior is identical to before the rename.

**Acceptance Scenarios**:

1. **Given** a consuming application currently importing classes under `com.bookit.servicecalllogging`, **When** it updates its imports and dependency coordinate to the new namespace and rebuilds, **Then** compilation succeeds and the application's outbound HTTP calls are logged and measured exactly as before.
2. **Given** the starter's auto-configuration is picked up by Spring Boot, **When** a consuming application starts, **Then** the auto-configuration classes are discovered and activated under their new fully-qualified names with no missing-bean, duplicate-bean, or misordered-configuration errors.

---

### User Story 2 - Maintainer verifies no functional regression after the rename (Priority: P2)

The maintainer runs the full existing test suite after the rename to confirm that renaming the package did not alter any observable behavior (logging fields, metrics tags, header propagation, envelope/response-code classification, etc.).

**Why this priority**: Confirms the "no core functionality change" constraint the requester explicitly stated, and satisfies the project's existing quality gates before merge.

**Independent Test**: Can be tested by comparing the full test suite's pass/fail results and coverage percentage immediately before and immediately after the rename.

**Acceptance Scenarios**:

1. **Given** the full test suite passes under the old package, **When** the rename is applied and the suite is re-run, **Then** the same tests pass with the same assertions — only namespaces differ.
2. **Given** the project's auto-configuration coverage gate requires full coverage, **When** the rename is complete, **Then** the coverage gate still reports the same result as before the rename.

---

### User Story 3 - Reader of project documentation sees a consistent namespace (Priority: P3)

Anyone reading the README or other living documentation sees only the new package name, with no leftover mentions of the old namespace that could mislead a new integrator.

**Why this priority**: Prevents confusion for new adopters, but carries lower risk than the functional and migration concerns above.

**Independent Test**: Can be tested by searching the repository's living documentation for the old package string and confirming zero matches.

**Acceptance Scenarios**:

1. **Given** the README previously referenced `com.bookit`, **When** the rename is complete, **Then** the README references only the `com.telecelghana.play.app.common` equivalent.

---

### Edge Cases

- How does the rename affect a consuming application that references starter classes by fully-qualified name, such as in a Spring Boot auto-configuration exclusion list or a component-scan filter?
- What happens to generated auto-configuration registration metadata (the file listing auto-configuration classes for Spring Boot to load) and any hard-coded fully-qualified class name strings in source or test code?
- What happens to already-published historical spec/plan/task records describing earlier, already-shipped features under the old package name? (See Assumptions.)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The starter's Java packages MUST be renamed so that every package currently rooted at `com.bookit` (e.g., `com.bookit.servicecalllogging.*`) is rooted at `com.telecelghana.play.app.common` instead (e.g., `com.telecelghana.play.app.common.servicecalllogging.*`), preserving the remainder of each package and class path unchanged.
- **FR-002**: Every reference to the old package name MUST be updated consistently across the starter, including main source, test source, build/dependency coordinates, Spring auto-configuration registration metadata, and living documentation (e.g., README).
- **FR-003**: The rename MUST NOT alter any observable behavior of the starter — the same configuration properties, logging fields, metrics, header propagation, and envelope/response-code classification MUST continue to work identically under the new package.
- **FR-004**: All existing automated tests MUST continue to pass after the rename, with no changes to test assertions beyond updated import/package statements.
- **FR-005**: The starter MUST continue to auto-configure correctly when pulled into a consuming Spring Boot application, with no duplicate, missing, or misordered auto-configuration entries introduced by the rename.
- **FR-006**: No public class, method, property key, or configuration key simple name MUST change as part of this effort other than the package prefix itself — e.g., `ServiceCallLoggingProperties` and existing configuration property keys keep their current names.
- **FR-007**: The rename MUST be applied as a clean, one-time replacement of the old package prefix with the new one — no backward-compatible alias or forwarding classes are retained in the old package.
- **FR-008**: Historical spec/plan/task documents describing already-shipped features are out of scope for this rename and MAY continue to reference the old package name as an accurate historical record. **Exception**: where the README links such a document as *live consumer instructions* rather than as a planning record, the consumer-facing code blocks in it MUST be updated — a document the README sends an integrator to must not hand out a dependency coordinate or package that no longer resolves. Two files meet this test and were updated: `specs/001-outbound-http-observability/quickstart.md` (the `groupId` in its Step 1 dependency snippet, linked from README's "Validating an integration") and `specs/001-outbound-http-observability/contracts/response-code-extractor-spi.md` (the `package` declaration in its interface listing, linked from README's "Replacing the responseCode extractor"). The remaining old-package references — in `specs/001`'s `plan.md` and `tasks.md`, and `specs/002`'s `data-model.md` and `research.md` — are pure planning records that nothing links as instructions, and stay untouched.

### Key Entities

Not applicable — this feature is a structural rename of existing code artifacts and does not introduce or change any data entities.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of source, test, build-configuration, and living-documentation references to the old package name are replaced; zero occurrences of the old package name remain outside historical, already-shipped spec records.
- **SC-002**: 100% of the pre-existing automated test suite continues to pass after the rename, with the same pass rate and coverage percentage measured immediately before the rename.
- **SC-003**: A sample consuming application that switches to the new package and coordinate observes zero difference in logged fields, metric names/tags, or HTTP behavior compared to before the rename.
- **SC-004**: The rename can be completed and verified (full build and test suite green) without any change to a functional test's expected outcome.

## Assumptions

- Historical spec/plan/task documents for already-shipped features (`specs/001-outbound-http-observability`, `specs/002-configurable-envelope-fields`, `specs/003-uri-operation-latency-telemetry`) are left unchanged as an accurate historical record of what was built at the time; only currently active source code, tests, build files, and living documentation are updated to the new package.
- The Maven `groupId` (currently `com.bookit`) is treated as a "use/reference" of the package and is renamed to `com.telecelghana.play.app.common` alongside the Java package statements, since it is the coordinate a consuming team uses to declare the dependency.
- The Maven `artifactId` (`service-call-logging-spring-boot-starter`) is unchanged — only the `groupId`/package prefix changes.
- The starter has not yet published its `1.0.0` release, so this rename can be delivered as a direct, breaking change to import paths and dependency coordinates without needing a deprecation/compatibility-shim period.
- "Core functionality" refers to the starter's runtime behavior (what it logs, measures, and how it activates); it does not include the package/namespace names themselves, which this feature intentionally changes.
