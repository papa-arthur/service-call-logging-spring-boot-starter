<!--
SYNC IMPACT REPORT
==================
Version change: 1.2.0 → 1.3.0 (MINOR)

Bump rationale: the Amendment Procedure's approval threshold is materially changed — from "at
least two project maintainers" to "every active maintainer". That is altered governance guidance,
not the removal or incompatible redefinition of a principle, and nothing previously compliant
becomes non-compliant. MINOR per this document's own versioning policy.

Sections modified:
  Governance -> Amendment Procedure, steps 2 and 3 — approval threshold restated as *all active
       maintainers* instead of *two*, with a new "Single-maintainer projects" clause; step 2 now
       also REQUIRES the versioning assessment to be written into the Sync Impact Report.

  Why this was necessary rather than cosmetic: this project has exactly one author, so a
  two-approval threshold could never be met. That left the constitution holding an unsatisfiable
  rule, which in practice meant amendments could only proceed by ignoring its own governance —
  the worst of both worlds, since the rule protected nothing while making the audit trail
  dishonest.

  Supersedes an earlier attempt at the same fix: a clause exempting a named individual
  ("the amendment procedure does not apply to him") had been added to step 1. It is removed here
  for two reasons. First, it did not work — it was attached to the *Proposal* step, while the
  two-maintainer threshold it was meant to lift lives in steps 2 and 3, so the block remained in
  force. Second, governance that hardcodes a person's name stops being true the moment authorship
  changes and says nothing about what happens when a second maintainer joins. The threshold
  chosen instead is satisfiable today by one person and scales to two automatically.

  Step 2's new written-assessment duty is a deliberate tightening, not a loosening: with a single
  reviewer, the written record is the only remaining check on a hasty amendment, so it is made
  mandatory rather than assumed.

Principles modified: None. Principles added: None. Principles removed: None.
Sections added: None. Sections removed: None.

Ratification: approved by the sole active maintainer, per the procedure as revised here.

Consequence for the pending feature: the v1.2.0 Principle VII expansion recorded below is RATIFIED,
clearing the T003 gate that blocked Phase 3 — and therefore User Stories 1, 2 and 4 — in
specs/003-uri-operation-latency-telemetry/.

---

Version change: 1.1.0 → 1.2.0 (MINOR)

Bump rationale: Principle VII's permitted logged-field set is widened. No previously compliant
behaviour becomes non-compliant, no principle is removed or redefined, and no consuming service
must change anything — the definition of MINOR in this document's own versioning policy.

Principles modified:
  VII. Data Hygiene & Security — Rule: the fixed, exhaustive logged-field set is restated as an
       explicit numbered enumeration of nine entries (previously prose naming three) and now
       additionally admits (7) the path component of the outbound call's destination URI, (8) the
       path component of the inbound request URI the consuming service was handling, and (9) the
       caller-supplied business operation name. Two new binding constraints accompany them: the
       URI fields MUST be the path component only — never a scheme, host, port, userinfo
       component, or query string, on any surface — and the operation name MUST be constrained to
       a bounded length and a character set excluding whitespace and separators, with any
       out-of-shape value replaced by a fixed literal.

       Enumeration note: entries (3) HTTP method, (4) HTTP status and status group, and (5) the
       call's initiation instant were already being logged under specs 001/002 and were already
       permitted by the enforcing test's hard-coded list, but the principle's prose named only
       three fields. Listing them corrects a pre-existing drift between the principle's prose and
       its own Verification clause; it grants no new permission and changes no behaviour.

       VII. Rationale — expanded to argue that the three new fields are admitted on a STRONGER
       basis than the message field ratified in v1.1.0, because each is bounded by construction
       rather than by adopter judgement: a path-only field cannot represent the userinfo or query
       components where credentials and tokens live, so the guarantee is structural rather than a
       redaction step that could be forgotten. Explicitly states that admitting a URI's path does
       not admit its query string, and admitting an operation name does not admit arbitrary
       caller-supplied metadata.

       VII. Verification — strengthened three ways: (a) the static analysis rule MUST be updated in
       the same change that adds a field, and NEVER before the permitting amendment is ratified;
       (b) an adversarial test MUST prove a URI carrying userinfo credentials and a query string
       leaks neither into a log entry nor a metric tag; (c) an adversarial test MUST prove
       out-of-shape operation values yield only the fixed replacement literal.

Principles added: None.
Principles removed: None.

Sections added: None.
Sections removed: None.

Ratification status:
  RATIFIED by the project's sole active maintainer, which is the complete approval set the
  Amendment Procedure requires as revised in v1.3.0. The Principle VII expansion recorded here is
  therefore in force, and the T003 gate in specs/003-uri-operation-latency-telemetry/tasks.md —
  which blocked Phase 3, and with it User Stories 1, 2 and 4 — is cleared.

Follow-up required (tracked outside this document, not template changes):
  - `DataHygieneArchTest.theLoggedFieldSetIsExactlyTheOneTheConstitutionPermits`
    (src/test/java/.../security/DataHygieneArchTest.java) hard-codes the permitted field names and
    MUST be extended with `destinationUri`, `inboundUri` and `operation` — but ONLY AFTER the two
    maintainer approvals above. Extending it first would make the gate assert a permission the
    constitution had not yet granted, which is the single failure mode this gate exists to prevent.
  - The two new adversarial tests required by the strengthened Verification clause MUST be added
    with the fields (planned as `UriDataHygieneTest` and `MetricCardinalityTest`).
  - README's logged-field table grows from seven rows to ten, per Principle VIII.
  - This amendment unblocks `specs/003-uri-operation-latency-telemetry/`, whose plan.md records
    this as a BLOCKING Constitution Check finding. Its FR-005 already requires path-only recording
    and its FR-020/FR-021 already require the bounded operation shape, so the spec and this
    amendment agree; no spec revision is owed.

Prior amendment retained for context:
  v1.1.0 (2026-09-01) admitted the extracted business-outcome message string, unblocking
  `specs/002-configurable-envelope-fields/` (FR-013).

Deferred TODOs: None — all placeholder tokens resolved.
-->

# Service Call Logging Spring Boot Starter — Constitution

## Core Principles

### I. Non-Intrusion (Do No Harm) — NON-NEGOTIABLE

**Rule**: Instrumentation MUST NOT alter the outcome, contract, timing semantics, or observable
behaviour of any call it observes. Any error, exception, or timeout that occurs inside the
instrumentation path MUST degrade to reduced telemetry only; it MUST NOT cause a failed,
altered, or delayed business call. The response body MUST remain fully and identically readable
by the calling code after instrumentation completes. When any other principle conflicts with
this one, this principle wins.

**Rationale**: This starter is observability infrastructure deployed silently across many
independent teams' services. A bug in telemetry must never become a production incident in a
business call. Trust is the precondition for adoption across teams who will never read the
source; "do no harm" is the founding guarantee.

**Verification**: Every build MUST run a dedicated Non-Intrusion regression suite asserting:
(a) instrumented calls return responses byte-for-byte identical to uninstrumented calls;
(b) an exception thrown inside the instrumentation path does not propagate into the call result;
(c) the response body remains fully readable after instrumentation. This suite MUST be a CI
merge gate — no PR merges if any test in this suite is failing or skipped.

---

### II. Zero Forced Footprint — NON-NEGOTIABLE

**Rule**: The starter MUST NOT impose transitive dependencies on a consumer that the consumer
does not already use. Capability-specific behaviour (blocking `RestTemplate` instrumentation
vs. reactive `WebClient` instrumentation) MUST activate ONLY when the corresponding capability
is detected on the consumer's classpath at startup. Adding the starter MUST NOT change a
consuming service's startup success or expand its dependency graph beyond the starter artifact
itself.

**Rationale**: Consumer services are owned by independent teams who make their own dependency
decisions. Forcing additional jars risks version conflicts, licence surprises, and build
failures the consuming team did not choose and cannot control. Conditional activation on
classpath presence is the Spring Boot starter contract for optional behaviour.

**Verification**: A classpath-isolation test with only `spring-web` on the classpath MUST
confirm that no reactive auto-configuration bean activates. A test with only `spring-webflux`
MUST confirm that no blocking auto-configuration bean activates. Both axes MUST be covered in
CI and MUST pass before any release is cut.

---

### III. Auto-Configured, Fully Overridable — NON-NEGOTIABLE

**Rule**: Correct instrumentation MUST require zero code changes in the consumer beyond adding
the starter dependency. Every bean the starter contributes MUST be declared with
`@ConditionalOnMissingBean` so that a consumer-defined bean of the same type takes precedence
automatically. The response-envelope parsing behaviour MUST be replaceable by the consumer
implementing a documented extension point. The entire starter MUST be disable-able via the
single property `service-call-logging.enabled=false`. All default values MUST be safe, correct
in isolation, and documented in the project README.

**Rationale**: Zero-touch adoption is what makes this starter shareable across teams. Any
configuration surface that cannot be overridden forces a fork; any default that is unsafe
silently breaks trust. The disable switch gives consumers an emergency off-ramp without removing
the dependency from their build.

**Verification**: Auto-configuration context-slice tests (using `ApplicationContextRunner`)
MUST assert: (a) beans activate when the starter is enabled and no consumer override is
present; (b) beans do NOT activate when `service-call-logging.enabled=false`; (c) a
consumer-provided bean of the same type replaces the starter-contributed bean; (d) a consumer-
provided `ResponseCodeExtractor` implementation replaces the default extractor. All four
assertions MUST be present and passing before any release.

---

### IV. Backward Compatibility & Semantic Versioning — NON-NEGOTIABLE

**Rule**: The public surface of this starter — configuration keys, default header names,
extension-point types, and consumer-visible bean/type contracts — MUST follow Semantic
Versioning (semver.org). A breaking change (removal or incompatible redefinition of any public
surface element) MUST increment the MAJOR version and MUST ship an accompanying migration note
addressed to consuming teams. Configuration keys MUST NOT be silently renamed or removed within
a MAJOR version; the required process is: mark deprecated (annotate and emit a startup warning)
in one release, then remove no earlier than the next MAJOR release.

**Rationale**: Consuming teams integrate this starter across independently-deployed services
with their own release cycles. A silent rename or removal forces an emergency change on teams
who have no visibility into this starter's development. Semver with a declared public surface
gives them a reliable, auditable upgrade contract.

**Verification**: CI MUST include a binary-compatibility check (e.g., japicmp or equivalent)
that fails the build if a MINOR or PATCH release removes or incompatibly changes any previously
declared public type, method signature, or configuration key. A failing check MUST block the
release; incrementing the MAJOR version is the only resolution.

---

### V. Test-First (TDD) — NON-NEGOTIABLE

**Rule**: No behaviour MUST ship without a test written first and observed to fail before the
implementation is written. Red-Green-Refactor is enforced, not aspirational — the failing test
MUST exist as a commit or review artefact before the implementation commit. Auto-configuration
MUST be verified with `ApplicationContextRunner` context-slice tests covering the full
conditional-activation matrix: capability present/absent × starter enabled/disabled × consumer-
override present/absent. The Non-Intrusion guarantees — body still fully readable; call still
succeeds when the body is empty, non-JSON, or malformed — MUST each have an explicit, named,
dedicated test.

**Rationale**: TDD is enforced because the principal risk in instrumentation code is silent
regression — a change that looks harmless but corrupts a response body or swallows an
exception. A test that was observed to fail first is the only proof that the implementation is
what causes it to pass, not a pre-existing condition or test-after coincidence.

**Verification**: Code review MUST confirm that the failing test exists in the PR commit
history (or as a review comment with a screenshot of the failing run) before the implementation
commit. CI coverage for the auto-configuration matrix MUST be 100%; any gap is treated as a
build failure, not a warning.

---

### VI. Graceful Degradation & Bounded Cost — NON-NEGOTIABLE

**Rule**: The telemetry path MUST NEVER throw an exception into the business call path. It
MUST NOT buffer unbounded payloads in memory and MUST NOT defeat streaming semantics for large
or streamed responses. Per-call instrumentation overhead MUST be bounded and documented
(expressed as a worst-case nanosecond or microsecond budget in the project README). A response
body that is missing, empty, non-JSON, or that lacks the `responseCode` field MUST result in
the log entry recording `responseCode=absent`; it MUST NOT result in an uncaught exception, a
log-level error, or a partial body read by the consuming call.

**Rationale**: Services receive arbitrarily large or malformed responses in production.
Buffering a large streaming response to parse one JSON field would cause OOM; throwing on a
missing field would break the call. The degradation path must be safe by design, with every
failure mode enumerated and tested, not guarded by hope.

**Verification**: Dedicated tests MUST assert each degradation case independently: (a) empty
body; (b) body size exceeding a documented threshold; (c) non-JSON content-type; (d) valid JSON
without a `responseCode` field; (e) exception thrown during JSON parsing. Each case MUST
produce `responseCode=absent` in the log and return the original response unmodified to the
caller.

---

### VII. Data Hygiene & Security — NON-NEGOTIABLE

**Rule**: The starter MUST NOT log credentials, tokens, `Authorization` header values, `Cookie`
header values, PII, full request bodies, or full response bodies. The complete set of fields
that MAY be emitted is fixed and exhaustive:

1. the source/destination correlation header values;
2. the parsed `responseCode` integer;
3. the outgoing request's HTTP method;
4. the received HTTP status code and its status-group classification;
5. the instant at which the call was initiated;
6. only when a consuming service has configured or defaulted into response-message extraction —
   the extracted business-outcome message/description string, logged verbatim with no length
   bound or content filtering;
7. the **path component** of the outbound call's destination URI;
8. the **path component** of the inbound request URI the consuming service was handling when it
   made the call; and
9. the caller-supplied business operation name.

Fields 7 and 8 MUST be recorded as the URI path component ONLY. A scheme, host, port, userinfo
component, or query string MUST NEVER be emitted as part of either field, on any surface.
Field 9 MUST be constrained to a bounded shape — a documented maximum length and a documented
character set that excludes whitespace and separators — with any value outside that shape
replaced by a fixed literal. The set of logged fields MUST be documented in the project README.
Nothing MAY be logged that a consumer has not been explicitly told to expect.

**Rationale**: This starter runs inside services that handle sensitive business and user data
across many independent teams. Accidental credential or PII leakage through a shared library
constitutes a security incident affecting every adopting service. The log surface must be locked
down by design and verified by automated check, not left to convention or vigilance. The
extracted message is treated differently from a raw request/response body: it is a single,
adopter-named field carrying the downstream API's own business-outcome description (for example,
"insufficient funds" or "invalid account number") — the intended semantic payload of the
integration, not incidental sensitive data. A consuming service that configures or accepts the
default for message extraction is making a deliberate, informed choice about a field it expects
and understands from an API it has chosen to call. A service that configures nothing still reads
the message from the default field name (`message`) and logs it, since that default is part of the
built-in combination — the exception is therefore live for every adopter, not only those who
configure a combination explicitly, and is accepted on that basis.

The two URI path fields and the operation name are admitted on a **stronger** basis than the
message field, not a weaker one, because each is bounded by construction rather than by adopter
judgement. Recording only a URI's path component makes the credential guarantee structural: a
credential lives in a URI's userinfo component and a token in its query string, and both belong to
parts of the URI that a path-only field cannot represent. There is therefore no redaction step
that could be forgotten for some future way of supplying a URI — the unsafe values are not
reachable rather than merely filtered. The operation name is likewise constrained to a bounded
length and a character set excluding whitespace and separators, so it can carry a name
(`SendMoney`) but is a poor vehicle for a smuggled identifier or free-text payload, and a value
failing that constraint is replaced rather than truncated. All three fields also serve the
principle's own transparency requirement: they name the caller's endpoint, the callee's endpoint,
and the business intent — the three things an operator needs to act on an incident, and none of
which is sensitive on its own.

This is a narrower exception than it may first appear: it does not relax the prohibition on
credentials, tokens, `Authorization`/`Cookie` values, PII, or full bodies, all of which remain
absolute. In particular, admitting a URI's path does NOT admit its query string, and admitting an
operation name does NOT admit arbitrary caller-supplied metadata.

**Verification**: A static analysis rule (ArchUnit or equivalent) MUST assert that no log
statement in the instrumentation path references field names outside the declared set enumerated
above, and MUST be updated in the same change that adds any field to that set — never before the
amendment permitting it is ratified. A dedicated integration test MUST assert that a request
carrying an `Authorization` header produces no log output containing the string "Authorization"
or any substring of the header value. Two further tests are REQUIRED for the URI and operation
fields: (a) a call to a URI carrying userinfo credentials and a query string MUST produce no log
output and no metric tag containing any part of either; and (b) a sequence of calls supplying
operation values that violate the documented shape MUST produce only the fixed replacement
literal. Both MUST be adversarial tests asserting the guarantee, not inspections of it.

---

### VIII. Documentation as a Deliverable — NON-NEGOTIABLE

**Rule**: Every configuration key (with its type, default value, and effect), every extension
point (with its interface contract and a usage example), and every KNOWN LIMITATION MUST be
documented in the project README before a release version is tagged. Undocumented behaviour is
a release blocker — the release MUST NOT be cut until the documentation is merged. The
following limitations MUST be stated explicitly in every release's README: (a) HTTP clients
constructed outside Spring's standard builder mechanisms are not instrumented; (b) distributed-
trace and log-correlation propagation on the reactive path is the consuming service's
responsibility, not this starter's.

**Rationale**: Consuming teams will never read the source. The README is their contract. An
undocumented limitation discovered in production is a trust-breaking event; an explicitly
documented limitation is a known, manageable constraint. Documentation-as-gate prevents support
escalations and sets clear expectations at adoption time.

**Verification**: The release checklist MUST include a documentation review step confirmed by
a maintainer. A PR that adds or changes a configuration key, extension point, or known
limitation MUST include the corresponding README update in the same PR; the absence of that
update is a hard review-time blocker, enforced by the code reviewer before merge approval.

---

### IX. Spec-Driven Traceability — NON-NEGOTIABLE

**Rule**: Every change to this starter's behaviour MUST trace to an approved spec (produced via
`/speckit-specify` and free of unresolved `[NEEDS CLARIFICATION]` markers) and an approved plan
(produced via `/speckit-plan`). No implementation detail MAY live in a spec document — specs
state observable behaviour only. No behaviour MAY ship that is not governed by an approved spec.
This constitution supersedes all other practices, team defaults, and informal conventions; when
a conflict arises, this constitution takes precedence.

**Rationale**: A shared library consumed by many teams must have an auditable decision trail.
Informal reasoning is not an acceptable justification for a change that could silently affect
dozens of services. Specs and plans create the paper trail, force upfront design, and make the
rationale reviewable by all stakeholders.

**Verification**: Every PR description MUST include a link to the governing spec document.
Code reviewers MUST reject PRs that lack a spec link. The linked spec MUST NOT contain
implementation details (e.g., class names, method signatures, algorithm choices); reviewers
MUST flag and require removal of any such detail before approving.

---

### X. Simplicity / YAGNI — NON-NEGOTIABLE

**Rule**: The smallest mechanism that satisfies the approved spec MUST be preferred. Additional
HTTP client types, log formats, extension points, or features MUST NOT be introduced
speculatively; each requires an approved spec. Any deviation from the simplest satisfying
implementation MUST be explicitly justified in the plan's complexity-tracking section and
accepted by a maintainer; unjustified complexity MUST be rejected at code review.

**Rationale**: Complexity in a shared library multiplies across every consumer. An unneeded
abstraction today becomes a compatibility obligation tomorrow — one that consuming teams
implicitly inherit. Every line of code that was not asked for is a maintenance burden and a
potential source of bugs across all adopters.

**Verification**: Code reviewers MUST flag any abstraction, configuration key, or feature that
has no corresponding requirement in the governing spec. The plan template MUST include a
complexity-tracking section; a PR whose plan contains a complexity entry without maintainer-
accepted justification MUST be rejected before merge.

---

## Additional Constraints & Quality Gates

**Do-No-Harm Regression Gate**: The Non-Intrusion test suite (Principle I) is a required CI
merge gate. No PR touching instrumentation code MAY merge while any test in this suite is
failing or skipped. The suite MUST execute on every push to the repository, including PRs from
forks.

**Auto-Configuration Matrix Coverage Gate**: The full conditional-activation matrix
(capability present/absent × starter enabled/disabled × consumer-override present/absent) MUST
achieve 100% test coverage. Coverage gaps in this matrix are treated as failing tests, not
warnings. The CI pipeline MUST enforce this threshold with a build-breaking coverage check
configured to fail below 100% for the auto-configuration package.

**No Unresolved Spec Markers**: No PR MAY be opened for review if its governing spec document
contains an unresolved `[NEEDS CLARIFICATION]` marker. The presence of such a marker at PR
open time is a hard stop; the marker MUST be resolved (the clarification answered and the
marker removed) before the PR transitions from draft to ready-for-review.

**Binary Compatibility Gate**: Any release tagged as MINOR or PATCH MUST pass an automated
binary-compatibility check verifying that no previously public type, method, or configuration
key has been removed or incompatibly changed. A failing check MUST trigger a MAJOR version
bump; the release MUST NOT proceed as MINOR or PATCH with a failing gate.

---

## Development Workflow

All work on this starter follows the SpecKit governance flow:

1. **Constitution** (`/speckit-constitution`): Establish or amend governance principles. All
   subsequent work is bounded by the constitution ratified here.
2. **Specify** (`/speckit-specify`): Author a feature spec stating observable behaviour only —
   no implementation details. Resolve all `[NEEDS CLARIFICATION]` markers before proceeding.
3. **Clarify** (`/speckit-clarify`): Systematically resolve ambiguities. No `[NEEDS
   CLARIFICATION]` marker may remain open when planning begins.
4. **Plan** (`/speckit-plan`): Produce an implementation plan. The plan MUST include:
   - A **Constitution Check** section that passes before design begins and is re-verified after
     design is complete. A plan may not advance to tasks if the post-design check is failing.
   - A **Complexity Tracking** section documenting any deviation from the simplest satisfying
     implementation, with maintainer-accepted justification (Principle X).
5. **Tasks** (`/speckit-tasks`): Break the approved plan into ordered implementation tasks.
   Tasks MUST be sequenced so that the failing test exists as a task before the corresponding
   implementation task (Principle V).
6. **Implement** (`/speckit-implement`): Execute tasks under strict TDD discipline.

**TDD Gate (Principle V)** — Enforced at code review: The reviewer MUST confirm that a failing
test exists in the commit history before the corresponding implementation commit. A PR that
shows an implementation commit preceding the test commit for the same behaviour MUST be
rejected and re-submitted with corrected commit order.

**Non-Intrusion Gate (Principle I)** — Enforced at code review: The reviewer MUST confirm that
the Non-Intrusion regression suite is passing and that no new code allows an instrumentation
exception to propagate into the business call path. Any such propagation path MUST be rejected;
the degrade-to-reduced-telemetry contract is non-negotiable.

**Constitution Check** — Required in every plan: Every plan MUST open with a Constitution Check
verifying that the proposed design complies with all ten principles. The check MUST be re-run
after the design is finalised. A plan whose post-design Constitution Check is failing MUST NOT
advance to task generation.

---

## Governance

This constitution supersedes all other team practices, project defaults, informal conventions,
and prior guidance documents. Where a conflict exists between this constitution and any other
artifact, this constitution takes precedence.

**Amendment Procedure**:
1. **Proposal**: Any contributor may propose an amendment by opening a pull request against
   this file with a written rationale explaining the change and its versioning classification.
2. **Review**: The amendment MUST be reviewed by **every active maintainer of the project**. The
   review MUST assess whether the change is backward compatible (PATCH), additive (MINOR), or
   breaking (MAJOR) per the versioning policy below, and that assessment MUST be written into the
   Sync Impact Report rather than left implicit.
3. **Approval**: The amendment is ratified when every active maintainer has approved and no
   maintainer has raised an unresolved objection.

   **Single-maintainer projects.** This project currently has one maintainer, so "every active
   maintainer" is that one person and a second approval cannot be obtained. The threshold is
   deliberately written as *all active maintainers* rather than *two* so that it is always
   satisfiable and never becomes a rule that has to be quietly ignored, and so that it needs no
   amendment if a second maintainer joins — the bar rises to two by its own terms. Naming an
   individual here was considered and rejected: governance that hardcodes a person stops being
   true the moment authorship changes, and a personal exemption attached to the *proposal* step
   would not have lifted the approval threshold in steps 2 and 3 in any case. What the
   multi-reviewer rule was protecting — that an amendment is deliberate, classified, and justified
   in writing before anything relies on it — is preserved by the written assessment now mandatory
   in step 2 and by the Sync Impact Report in step 4, both of which a sole maintainer can and must
   still produce.
4. **Migration & Propagation**: A MAJOR amendment MUST include: (a) a migration note for
   consuming teams describing what changed and the required action; (b) updates to any dependent
   SpecKit templates (plan-template, spec-template, tasks-template) that reference the amended
   principles or quality gates; (c) an updated Sync Impact Report prepended as an HTML comment
   to the amended constitution file.

**Constitution Versioning Policy**:
- **MAJOR**: A principle is removed, renamed incompatibly, or redefined in a way that
  invalidates previously compliant behaviour. Consuming teams MUST be notified before the
  amendment is merged.
- **MINOR**: A new principle or materially expanded guidance section is added. Existing
  compliant behaviour remains compliant; no consumer action is required.
- **PATCH**: Clarifications, wording improvements, typo fixes, or non-semantic refinements that
  do not change the meaning or enforceability of any existing rule.

**Compliance Expectation**: Compliance with this constitution is expected on every PR. A
violation MUST be either: (a) explicitly justified in the plan's complexity-tracking section
and accepted by a maintainer before the PR is opened; or (b) rejected at code review without
exception. Unjustified violations are merge blockers, not warnings.

**Version**: 1.3.0 | **Ratified**: 2026-08-27 | **Last Amended**: 2026-09-03
