# Phase 0 Research: Outbound Call URI, Operation and Latency Telemetry

**Feature**: `003-uri-operation-latency-telemetry` | **Date**: 2026-09-03 | **Spec**: [spec.md](spec.md)

Every finding below was verified against the versions actually resolved for this build
(Spring Boot 3.3.13 → Spring Framework 6.1.21, Micrometer 1.13.x), not recalled from
documentation. Where a claim was checked by inspecting a jar, the check is stated.

---

## §1 Destination URI templated form — blocking path (`RestTemplate`)

**Finding (verified)**: a `ClientHttpRequestInterceptor` **cannot** obtain the URI template on
Spring Framework 6.1.21. Inspection of `spring-web-6.1.21.jar`:

- `org.springframework.http.client.ClientHttpRequest` carries **no** `getAttributes()` member
  (the per-request attribute map that would convey a template arrived later than this baseline).
- `org.springframework.web.client.RestTemplate` references `UriTemplateHandler` and
  `resolveUriTemplate`, but never publishes the resolved template anywhere the interceptor can
  read it. The interceptor is handed an already-expanded `java.net.URI`.

Consequence if nothing is done: on the blocking path the templated form can never be determined,
so FR-004 applies to **every** call — raw path in the log entries, and the `unresolved`
placeholder in the metric tag for all of them. That is literally FR-003/FR-004 compliant, but it
makes the destination-URI metric tag a constant on the starter's primary client path, which fails
**SC-001** ("an operator can break outbound call volume down by ... which downstream endpoint was
called ... as four independent dimensions").

**Decision**: capture the template by decorating the `RestTemplate`'s `UriTemplateHandler` inside
the existing `RestTemplateCustomizer`, recording `(template, expandedUri)` into a `ThreadLocal`
that the interceptor reads and always clears.

**Rationale**:

- It yields the *true* template the calling code wrote, not a guess.
- The starter already owns a `RestTemplateCustomizer` (`serviceCallLoggingRestTemplateCustomizer`),
  so the integration point exists and no new consumer-visible wiring is introduced.
- `expand()` and `intercept()` run on the same thread in a synchronous `RestTemplate` exchange, so
  a `ThreadLocal` hand-off is sound.
- **Staleness is eliminated structurally, not by discipline**: the interceptor uses the captured
  template only when the stored expanded URI `equals` the URI on the request it is intercepting.
  A `RestTemplate.exchange(URI, ...)` overload bypasses the handler entirely, so no `expand()`
  occurs, the stored pair (if any) belongs to an earlier call, the equality check fails, and the
  call correctly degrades to FR-004. This is the same reasoning FR-007 applies to the inbound URI.
- This is the mechanism Spring Boot's own historical `RestTemplate` metrics used, so it is a proven
  pattern rather than an invention.

**Alternatives considered and rejected**:

| Alternative | Rejected because |
|---|---|
| Accept the raw path everywhere (do nothing) | FR-compliant but fails SC-001 on the primary client path; the destination-URI tag would be a constant `unresolved` for every blocking consumer |
| Heuristically normalise the raw path (replace numeric/UUID segments with `{id}`) | Invents behaviour no requirement asks for; silently wrong for legitimately numeric path segments; produces a value that looks like a template but is not the one the developer wrote |
| Upgrade the Spring Boot parent to gain per-request attributes | Out of scope for this feature, and a parent upgrade is a far larger blast radius for every consumer than the mechanism chosen |

**Cost accepted (Principle X entry)**: one decorator class plus one `ThreadLocal`. Tracked in
`plan.md` → Complexity Tracking.

---

## §2 Destination URI templated form — reactive path (`WebClient`)

**Finding (verified)**: `DefaultWebClient` publishes the template as a request attribute. String
constants `URI_TEMPLATE_ATTRIBUTE` and `.uriTemplate` are present in
`spring-webflux-6.1.21.jar` → `DefaultWebClient.class`, giving the attribute key
`org.springframework.web.reactive.function.client.WebClient.uriTemplate`.

**Decision**: read that attribute from `ClientRequest.attributes()` in the exchange filter. Use the
literal key string rather than referencing `DefaultWebClient` (a package-private-ish internal), so
no internal Spring type becomes a compile dependency.

**Rationale**: no `ThreadLocal` and no decorator needed on this path — the framework already
carries the template on the request. A `WebClient` call built from a pre-made `URI` has no such
attribute and degrades to FR-004, which is correct.

**Consequence worth stating plainly**: the two paths reach the same outcome by different means, so
each needs its own test for "template available" and "template absent".

---

## §3 Inbound request URI

**Decision**: resolve from `org.springframework.web.context.request.RequestContextHolder`, reading
the request attribute `org.springframework.web.servlet.HandlerMapping.bestMatchingPattern` via
`RequestAttributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST)`.

**Rationale**:

- `RequestContextHolder` and `RequestAttributes` both live in **spring-web**, which the blocking
  path already requires — no new dependency (Principle II).
- The best-matching-pattern attribute is addressed **by literal string**, deliberately. Referencing
  `org.springframework.web.servlet.HandlerMapping` would pull in **spring-webmvc**, and
  referencing `ServletRequestAttributes` would pull in **jakarta.servlet-api** — both would be new
  forced footprint for a consumer that has `RestTemplate` but no servlet stack (a batch or worker
  service). Reading through the `RequestAttributes` interface avoids both.
- `RequestContextHolder` is populated by `FrameworkServlet` for every `DispatcherServlet` request
  and cleared when that request completes.

**Why this satisfies FR-007 (never report a stale value) without extra machinery**:
`RequestContextHolder`'s holder is a plain (non-inheritable) `ThreadLocal` by default, and the
servlet resets it at the end of request handling. A pooled or `@Async` thread therefore observes
`null` — not a previous request's attributes — so the fallback path is taken naturally. The one
configuration that would break this is a consumer explicitly enabling inheritable thread-context;
that is the consumer's own choice and is called out as a documented limitation.

**FR-008 (no propagation across hand-off)**: nothing is done to carry the value across threads. An
async-dispatched call reports `unknown`, as the spec requires.

**Reactive inbound URI**: not attempted. The reactive equivalent
(`ServerWebExchange` attribute reached through the Reactor context) is exactly the context
propagation the starter's existing documented limitation leaves with the consuming service, and the
spec sanctions the fallback here. Deliberately YAGNI (Principle X).

---

## §4 Recording the path component only (FR-005)

**Decision**: for both dimensions record `URI#getRawPath()` (or, for a captured template, the
template's path portion), never the authority, scheme or query.

**Rationale**: FR-005 makes the credential guarantee **structural**. Userinfo lives in the
authority and the query string is a separate URI component, so taking the path alone means there is
no redaction step that could be forgotten for some new way of supplying a URI. This is materially
stronger than stripping, and it is what SC-008 verifies adversarially.

**Note on the template form**: a template such as `https://host/accounts/{id}` must be reduced to
`/accounts/{id}`. The template is a `String`, not a `URI`, and may be relative or absolute, so path
extraction handles both without parsing it as a URI (a template containing `{}` is not a legal URI
and `URI.create` on it can throw — a real trap, since throwing is forbidden by FR-037).

---

## §5 Latency distribution

**Decision**: a Micrometer `Timer` named `<prefix>.latency`, recorded with `System.nanoTime()`
deltas.

- **Unconfigured** → `Timer.Builder#publishPercentileHistogram()`, which delegates bucket choice to
  Micrometer, exactly as the clarification session decided (FR-012).
- **Configured** → `Timer.Builder#serviceLevelObjectives(Duration...)` from the property list
  (FR-013).
- Empty or unusable configuration → fall through to the unconfigured branch (FR-014), never a
  startup failure.

**Rationale**: a `Timer` is the one meter type that carries count, total time and a bucketed
distribution under a single name and tag set, so percentile and "share within boundary" views
(FR-011) come from the same series. `System.nanoTime()` is monotonic and immune to wall-clock
adjustment, unlike the existing `Instant.now()` used for the record timestamp — both are kept, for
different jobs.

**⚠ Cardinality finding that the release must not ship silently**:
`publishPercentileHistogram()` on a `Timer` expands into a large fixed bucket ladder (tens of
buckets — on the order of 70 for a Prometheus registry across Micrometer's default 1 ms–30 s
range). Series count is *buckets × every tag combination*. Worked example for a modest service —
20 destination templates, 15 inbound templates, 5 operations, 3 outcomes, 6 status groups:

```
20 × 15 × 5 × 3 × 6  = 27,000 tag combinations
27,000 × ~70 buckets ≈ 1.9 million time series
```

Real traffic does not fill the full cross product (inbound and destination URIs are correlated),
but the order of magnitude is the point: this is enough to destabilise a consumer's metric store,
which engages **Principle VI (Bounded Cost)**. Three mitigations, all required:

1. FR-015's documentation duty is extended to carry an explicit cardinality warning with this
   arithmetic, and to recommend that a high-dimensionality service configure a short explicit
   bucket list instead of taking the delegated default.
2. The distinct-operation cap (§6) bounds one of the five factors by construction.
3. FR-004's placeholder bounds the two URI factors to the consumer's own finite route inventory.

This does not reverse the clarification decision — the default stays delegated — but the risk is
recorded here and raised to the maintainer in `plan.md` → Risks, because "the default could hurt a
consumer who changes nothing" is precisely the kind of thing Principle I's trust argument exists to
prevent.

---

## §6 Business operation resolution

**Decision**: read `X-Operation` with `getFirst(...)` on both paths, then apply, in order: shape
bound → distinct-value cap → value or `undefined`.

- `getFirst` satisfies FR-025 (a repeated header yields exactly one deterministic value) with no
  extra logic.
- Shape bound (FR-020): ≤ 64 chars, `[A-Za-z0-9._-]` only. Implemented as a hand-rolled character
  scan, **not** a regex — a regex on caller-controlled input is a needless catastrophic-backtracking
  surface, and a linear scan is both faster and obviously bounded.
- Distinct-value cap (FR-021): a `ConcurrentHashMap`-backed key set, admitting a new value only
  while `size() < 100`. First-come-first-served, no eviction, no ranking (FR-021 forbids ranking).
  Bounded at 100 × ≤64 chars ≈ a few KB — trivially within Principle VI.
- The header is only ever **read**. Nothing writes, removes or rewrites it (FR-024), so a value the
  cap rejects still reaches the destination untouched.

**Rationale for a per-process cap rather than a time window or LRU**: an LRU would let a
high-volume junk caller evict a real operation, making a dashboard's meaning change under load —
which FR-021 explicitly rules out. A fixed admit-then-stop set is the simplest mechanism that makes
SC-009 true.

---

## §7 Two log entries per call

**Decision**: `CallLogger` gains a `logRequest(...)` method emitting `outbound-request`; the
existing `log(...)` is renamed in output only — same method, new `outbound-req-response` prefix.
`logWarn(...)` and its `outbound-call-instrumentation-error` text are **left exactly as they are**
(FR-030, FR-033).

**Rationale / compatibility consequence**: `CallLogger` is a concrete, consumer-overridable bean.
Anyone who extends it and overrides `log` will keep working but will not emit the send-time entry
until they also override `logRequest`. With 1.0.0 unpublished there is nobody in that position
today and so no migration note is owed (§10) — but it is still a real trap for the first adopter
who subclasses `CallLogger`, so it belongs in the README's extension-point documentation rather
than being left to be discovered.

**Ordering and non-intrusion**: the send-time entry is emitted *before* dispatch, inside the same
guarded pattern as every other telemetry step, so a logging failure cannot delay or break the call
(FR-037, FR-038).

---

## §8 The `OutboundCallRecord` / ArchUnit constraint

**Finding (verified in `DataHygieneArchTest`)**: the `logging` package is forbidden by an ArchUnit
rule from depending on `org.springframework.http..`, `org.springframework.web..`, `io.micrometer..`
or `com.fasterxml..`, and a separate constant-pool scan fails the build if **any** compiled main
class carries the literal `Authorization`, `Cookie`, `Bearer`, `authorization` or `cookie`.

**Decision**: the three new fields enter `OutboundCallRecord` as plain `String`s, and all URI and
operation resolution lives in new packages outside `logging`. No new literal in this feature comes
near the forbidden list.

**Also required**: `DataHygieneArchTest.theLoggedFieldSetIsExactlyTheOneTheConstitutionPermits`
hard-codes the permitted field names. It must be extended in the same change that adds the fields —
and it must be extended *after* the constitution amendment in §9, never before, or the test would
be asserting something the constitution does not yet permit.

---

## §9 Principle VII amendment — ratified (originally drafted as proposed wording)

> **Update:** ratified as constitution v1.2.0, made ratifiable on this single-maintainer project by
> the v1.3.0 Amendment Procedure change. The wording below is the amendment as proposed and, in
> substance, as adopted — see `.specify/memory/constitution.md`'s Sync Impact Report for the
> authoritative final text and the exact numbered-enumeration form it shipped in.

This feature adds three fields to a set Principle VII declares "fixed and exhaustive". As with
spec 002's message field, this is a conflict with a **NON-NEGOTIABLE** principle, so it is *not*
eligible for a Complexity Tracking justification — the Amendment Procedure is the only correct
path. Proposed replacement for the enumeration sentence:

> The complete set of fields that MAY be emitted is fixed and exhaustive: the source/destination
> correlation header values, the parsed `responseCode` integer, the extracted business-outcome
> message/description string (only when a consuming service has configured or defaulted into
> response-message extraction), the **path component** of the outbound call's destination URI, the
> **path component** of the inbound request URI the consuming service was handling, and the
> caller-supplied business operation name.

**Why this is a MINOR amendment (1.1.0 → 1.2.0)**: it widens what is permitted without
invalidating any previously compliant behaviour. Supporting rationale for the amendment PR: all
three additions are structurally incapable of carrying a credential or a query string, because
FR-005 records the path component only (§4) and the operation is constrained to
`[A-Za-z0-9._-]{1,64}` (§6). The exception is therefore narrower than the message-field exception
already ratified in v1.1.0 — the message is unbounded free text, whereas these three are bounded by
construction.

---

## §10 Versioning and the binary-compatibility gate

**Target release: 1.0.0 — unchanged. No version bump is owed.**

**1.0.0 has never been published and the starter has no consumers yet.** Principle IV governs
compatibility with a *released* public surface, so with no published artifact there is no baseline
to break and nothing to migrate anyone from. The version stays at 1.0.0 and this feature ships
inside the not-yet-cut first release.

This also makes the japicmp gate correctly inert: `pom.xml` already carries `<skip>true</skip>`
with the comment *"Skipped for the initial 1.0.0 release: no published baseline artifact exists
yet. Once 1.0.0 is released, set `<skip>false</skip>`."* That comment is accurate as written — no
change needed, and no gate exclusion is being invoked to hide anything.

**Retained for the release after this one.** The four changes below *would* each be breaking
against a published 1.0.0. Once 1.0.0 is actually cut, every one of them becomes a MAJOR-bump
trigger and this table becomes live again:

| Change | Why it would be breaking against a published baseline |
|---|---|
| `outbound-call` → `outbound-request` + `outbound-req-response` | Consumers' log parsing, dashboards and alerts would match on the old prefix (FR-042) |
| Existing counter gains three tags (FR-028) | Changes the identity of an already-published series; queries asserting a complete tag set would need revising (FR-029) |
| `OutboundCallRecord` gains record components | Canonical constructor signature changes — japicmp would flag it |
| `ServiceCallLoggingProperties.Metrics` gains record components | Same; it is a public constructor-bound record |

No configuration key is removed or renamed in any case, so no deprecation cycle is owed either
way.
