# Phase 0 Research: Rename Base Package to com.telecelghana.play.app.common

No items in the plan's Technical Context were left as `NEEDS CLARIFICATION` — the spec's
Assumptions section already resolved every open question with a documented, low-risk default
(see spec.md). This document records the rationale for each of those decisions, plus the
mechanical approach for carrying out the rename safely.

## 1. Scope of the rename: groupId included?

- **Decision**: Rename both the Java package root (`com.bookit` → `com.telecelghana.play.app.common`)
  and the Maven `groupId` in `pom.xml` (currently `com.bookit`).
- **Rationale**: The `groupId` is the literal string `com.bookit` and is the coordinate a
  consuming team's `pom.xml`/`build.gradle` uses to declare the dependency — it is as much a
  "use/reference" of the old package as any Java `import` statement, and leaving it unchanged
  while renaming the Java package would produce an inconsistent artifact (new import paths, old
  Maven coordinate).
- **Alternatives considered**: Rename only the Java package, leave `groupId` as `com.bookit`.
  Rejected — it would leave the two most visible identifiers (the dependency coordinate a team
  adds to their build file, and the package they import) pointing at two different
  organizations, which is more confusing than either renaming both or neither.

## 2. Historical spec documents (001-003)

- **Decision**: Do not touch `specs/001-outbound-http-observability/`,
  `specs/002-configurable-envelope-fields/`, or `specs/003-uri-operation-latency-telemetry/`.
  They keep referencing `com.bookit` where they already do.
- **Rationale**: These documents are historical records of what was actually built and reviewed
  at the time (Principle IX, Spec-Driven Traceability treats specs as an audit trail). Rewriting
  them to say `com.telecelghana.play.app.common` would misrepresent history — at the time those
  features shipped, the package genuinely was `com.bookit`. This mirrors how source-control
  history is not rewritten when a later commit renames something.
- **Alternatives considered**: Global find-and-replace across the whole repository including
  historical specs. Rejected as it falsifies the historical record for no operational benefit —
  nothing reads those documents at runtime, and future readers benefit more from an accurate
  "as-built-at-the-time" record than from superficial consistency.

## 3. Backward compatibility / migration shims

- **Decision**: No shim, alias, or deprecated forwarding classes are retained in the old
  `com.bookit` package. The rename is a single, clean cut-over.
- **Rationale**: The project's own memory of the constitution notes the binary-compatibility gate
  (japicmp) is "legitimately skipped while 1.0.0 is unpublished" — i.e., there is no real external
  consumer on a previously published coordinate to protect yet. Adding shim classes would
  introduce new classes and dual-package surface area that the user explicitly did not ask for
  ("This should not change the core functionality of the starter" — read as "do not add new
  functionality", not just "do not change existing functionality").
- **Alternatives considered**: Keep deprecated forwarding classes in `com.bookit.*` for one
  release. Rejected under Principle X (Simplicity/YAGNI) given there is no known published
  consumer yet; documented in plan.md's Complexity Tracking as a decision to revisit if an
  external consumer surfaces.

## 4. Mechanical approach to the rename

- **Decision**: Treat this as a structural move-and-rewrite:
  1. Move every file under `src/main/java/com/bookit/**` to the mirrored path under
     `src/main/java/com/telecelghana/play/app/common/**` (same for `src/test/java`).
  2. Update the `package` and `import` statements inside every moved file.
  3. Update **both** `com.bookit` sites in `pom.xml`: the `<groupId>`, and the JaCoCo
     `auto-configuration-matrix-gate` rule's `<include>` package pattern. Missing the second
     leaves the constitution's 100%-coverage gate matching nothing and passing vacuously.
  4. Update the single line in
     `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
     that names the auto-configuration class by FQCN.
  5. Update `README.md`'s dependency snippet (`groupId`) and the one FQCN example
     (`com.bookit.servicecalllogging.OverheadBenchmark`).
  6. Re-run the full test suite and confirm identical pass/fail results and coverage to a
     pre-rename baseline run.
- **Rationale**: This is the smallest, most mechanical sequence that satisfies FR-001–FR-006; it
  matches standard IDE/tooling support for package renames (move + statement rewrite) and keeps
  the change auditable as "rename, not rewrite."
- **Alternatives considered**: Recreate the class hierarchy from scratch under the new package.
  Rejected — needlessly risks introducing subtle behavioral differences; a straight move preserves
  bytecode-equivalent logic.

## Summary

All Technical Context items are resolved with no outstanding unknowns. The approach is a
mechanical, tooling-assisted package/groupId rename with no new abstractions, no compatibility
shims, and no change to historical spec documents.
