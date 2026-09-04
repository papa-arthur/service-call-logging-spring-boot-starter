# Quickstart: Validating the Package Rename

This guide proves the rename satisfies the spec's success criteria (SC-001–SC-004) end-to-end,
without duplicating the mapping already recorded in [contracts/public-surface-rename.md](contracts/public-surface-rename.md).

## Prerequisites

- JDK matching the project's configured Java version and Maven installed (same toolchain already
  used to build this starter today).
- A clean working tree, or the rename changes staged, so `mvn` operates on the intended state.

## Step 1 — Capture a pre-rename baseline

```bash
mvn -q clean verify
```

Record the total test count, pass count, and the JaCoCo coverage percentage for the
auto-configuration package. This is the baseline SC-002 and SC-003 are compared against.

## Step 2 — Apply the rename

Carry out the artifact transformations listed in [data-model.md](data-model.md) (source move,
`pom.xml` `groupId`, the `AutoConfiguration.imports` entry, and the README references). No test
assertions should change other than import statements and any assertion that literally checks an
FQCN string.

## Step 3 — Confirm zero remaining old-package references (SC-001)

```bash
grep -rIn "com[./]bookit" . --exclude-dir=target --exclude-dir=.git --exclude-dir=specs --exclude=README.md
grep -rnE '\bc\.b\.'       . --exclude-dir=target --exclude-dir=.git --exclude-dir=specs --exclude=README.md

# README is checked section-aware, because its migration note must quote the old package:
awk '/^### Base package renamed/{skip=1} /^## /{skip=0} !skip' README.md | grep -nE "com\.bookit|\bc\.b\."
```

Expected: no output from any of the three.

Three notes on why the commands are shaped this way:

- **README is excluded from the blanket sweep and checked separately.** Its "Base package renamed"
  migration note has to name `com.bookit` — that is what tells a consuming team what to change
  *from*. A blanket grep cannot distinguish that from a stale reference, so the `awk` filter drops
  the migration section and asserts nothing old survives anywhere else in the file.

- **All of `specs/` is excluded**, not just 001 and 002. `--exclude-dir` matches a directory
  *name*, not a path, so `--exclude-dir=specs/001-outbound-http-observability` silently has no
  effect. And `specs/004-rename-base-package/` legitimately contains the old name throughout,
  because those documents describe the rename.
- **The second sweep catches abbreviated forms.** Logback's `%logger{n}` pattern renders
  `com.bookit.servicecalllogging` as `c.b.s.`, which contains no `com.bookit` substring — so a
  literal search cannot see a stale log sample in the docs. This is exactly how two stale samples
  in README.md survived the first sweep.

## Step 4 — Re-run the full suite and compare to baseline (SC-002)

```bash
mvn -q clean verify
```

Expected: identical total test count and pass count to Step 1's baseline; JaCoCo coverage
percentage for the auto-configuration package unchanged (still 100%, per the existing
Auto-Configuration Matrix Coverage Gate).

## Step 5 — Confirm the starter still auto-activates in a consumer (SC-003, User Story 1)

Using the project's existing `ApplicationContextRunner`-based auto-configuration tests (e.g.
`ServiceCallLoggingAutoConfigurationTest`, `WebClientConfigurationActivationTest`,
`RestTemplateConfigurationActivationTest` under their new package), confirm they pass unmodified
in behavior. These already exercise the scenario a real consuming application would hit: the
starter's auto-configuration entry, found via the updated
`AutoConfiguration.imports` file, activates the same beans under the same conditions as before.

## Step 6 — Confirm documentation is consistent (User Story 3)

```bash
awk '/^### Base package renamed/{skip=1} /^## /{skip=0} !skip' README.md | grep -nE "com\.bookit|\bc\.b\."
```

Expected: no output — i.e. outside the migration note, README mentions only the new package. The
`\bc\.b\.` alternative matters: README's sample log output shows the Logback-abbreviated logger
category (`c.b.s.…`), which a plain `com.bookit` search cannot match.

Also confirm README carries the migration note for the names that changed as a *side effect* of
the rename (logger categories, the `@ConfigurationProperties`/actuator bean name, and string-form
auto-configuration exclusions). See the "Base package renamed" table in README.

## Success

All six steps passing means: every reference is updated (SC-001), the full test suite is
unaffected in outcome (SC-002), the starter still auto-configures correctly end-to-end (SC-003),
and the living documentation is consistent (User Story 3) — completing SC-004 (no open questions
remain by the time this guide passes).
