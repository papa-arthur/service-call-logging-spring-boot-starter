# Phase 1 Data Model: Rename Base Package to com.telecelghana.play.app.common

This feature introduces no business data entities (the spec's Key Entities section states
"Not applicable"). Instead, this document inventories the **artifact categories** the rename
touches, since those are the "entities" being transformed — each row is a class of artifact,
its identifying attribute, and the transformation rule applied to it.

## Artifact Categories

### 1. Java source file (main)
- **Identifying attribute**: file path under `src/main/java/com/bookit/**`; `package` declaration;
  any `import com.bookit....` statement.
- **Transformation**: file moves to the mirrored path under
  `src/main/java/com/telecelghana/play/app/common/**`; `package`/`import` statements rewritten to
  the new prefix; simple class/member names unchanged (FR-001, FR-006).
- **Count (measured after implementation)**: 24 files.

### 2. Java source file (test)
- **Identifying attribute**: file path under `src/test/java/com/bookit/**`; `package` declaration;
  any `import com.bookit....` statement; any FQCN string literal used in test assertions.
- **Transformation**: same move-and-rewrite as main source; test assertion logic and expected
  values are unchanged except where they assert an FQCN string (FR-004 — assertions on behavior
  must produce identical results, but an assertion that literally checks a class's fully-qualified
  name must be updated to expect the new FQCN, since that is what "identical behavior" means for
  such a check).
- **Count (measured after implementation)**: 45 files.

### 3. Build configuration (`pom.xml`) — **two** distinct sites
- **Identifying attribute (a)**: `<groupId>com.bookit</groupId>` (line ~14).
- **Identifying attribute (b)**: the JaCoCo `auto-configuration-matrix-gate` rule's
  `<include>com.bookit.servicecalllogging.autoconfigure</include>` (line ~261).
- **Transformation**: (a) rewritten to `<groupId>com.telecelghana.play.app.common</groupId>`, with
  `<artifactId>` and `<version>` unchanged; (b) rewritten to
  `com.telecelghana.play.app.common.servicecalllogging.autoconfigure`.
- **Why (b) is critical**: a JaCoCo `check` rule whose `<include>` matches no package evaluates
  nothing and passes. Renaming only the `groupId` would leave the constitution's
  Auto-Configuration Matrix Coverage Gate (`haltOnFailure=true`, 100% INSTRUCTION/LINE/METHOD)
  silently vacuous while the build still reported SUCCESS — a weakened gate that looks green.
  Verified live after the rename: the gate matches 3 classes at 100%.

### 4. Auto-configuration registration entry
- **Identifying attribute**: the single line in
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  naming `com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration`.
- **Transformation**: rewritten to the FQCN under the new package root; this file MUST be updated
  in the same change as the class move, or Spring Boot will fail to discover the auto-configuration
  (FR-005).

### 5. Living documentation reference (`README.md`)
- **Identifying attribute**: the two `com.bookit` occurrences — the dependency `groupId` snippet
  and the `OverheadBenchmark` FQCN example.
- **Transformation**: both rewritten to the new package/groupId (FR-002, User Story 3).

### 6. Historical spec/plan/task document (out of scope)
- **Identifying attribute**: files under `specs/001-outbound-http-observability/` and
  `specs/002-configurable-envelope-fields/` mentioning `com.bookit`.
- **Transformation**: **none** — explicitly excluded from this rename (FR-008; see research.md §2).

## Relationships / Ordering Constraints

- Category 4 (auto-configuration registration) MUST be updated atomically with category 1 (the
  `ServiceCallLoggingAutoConfiguration` class move) — an inconsistent intermediate state would
  break auto-configuration discovery.
- Categories 1 and 2 MUST be updated together per compilation unit (a test file importing a main
  class must move/rewrite in the same change as that main class, or the build will not compile).
- Category 3 (Maven `groupId`) has no compile-time dependency on the others but should land in the
  same change for a consistent artifact identity.

## Validation Rules

- After the transformation, a repository-wide search MUST return zero matches outside `specs/` and
  `README.md` (SC-001):
  `grep -rIn "com[./]bookit" . --exclude-dir=target --exclude-dir=.git --exclude-dir=specs --exclude=README.md`.
  README is excluded because its migration note must quote the old package to say what a consumer
  is migrating *from*; it is instead checked section-aware, asserting no old mention survives
  outside that note (see quickstart.md Step 3).
  All of `specs/` is excluded because 001/002 are protected historical records (FR-008) and 004
  necessarily quotes both the old and new names to describe the rename. Note `--exclude-dir`
  matches a directory *name*, not a path, so a slash-bearing value such as
  `--exclude-dir=specs/001-outbound-http-observability` silently has no effect.
- A second sweep MUST cover *abbreviated* forms that the literal search structurally cannot see —
  notably Logback's `%logger{n}` abbreviation of the old package (`c.b.s.`) in documentation
  samples: `grep -rnE '\bc\.b\.' . --exclude-dir=target --exclude-dir=.git --exclude-dir=specs`.
- The full test suite MUST report the same pass count and the same coverage percentage as the
  pre-rename baseline (SC-002).
