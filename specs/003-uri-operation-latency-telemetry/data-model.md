# Phase 1 Data Model: Outbound Call URI, Operation and Latency Telemetry

**Feature**: `003-uri-operation-latency-telemetry` | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Nothing here is persisted. Every type is a short-lived, in-memory projection of one outbound call,
except the operation admission set, which is the feature's only piece of process-lifetime state.

---

## 1. `OutboundCallRecord` (changed)

The exhaustive list of what the starter may log (Constitution Principle VII). Three components
added; every existing component keeps its name, type and meaning.

| Component | Type | New? | Meaning | Never null? |
|---|---|---|---|---|
| `source` | `String` | | Calling service name, or `unknown` | yes |
| `destination` | `String` | | Resolved destination service name | yes |
| `httpMethod` | `String` | | Outgoing request method | yes |
| `httpStatusCode` | `Integer` | | Received status, or null when no response arrived | no |
| `httpStatusGroup` | `String` | | `2xx`/`4xx`/`5xx`/`network-error` (also `1xx`/`3xx`) | yes |
| `responseCode` | `Integer` | | Parsed business code, or null | no |
| `message` | `String` | | Business outcome message, or null | no |
| `destinationUri` | `String` | **yes** | Path component of the call's destination — template form when known, else raw path, else `unknown` | yes |
| `inboundUri` | `String` | **yes** | Path component of the inbound request's matched pattern, else `unknown` | yes |
| `operation` | `String` | **yes** | Admitted operation name, else `undefined` | yes |
| `timestamp` | `Instant` | | When the call was initiated | yes |

**Invariants**

- The three new components are **never null and never empty** — a value the starter could not
  determine is the documented literal, so a log line's field count is constant (FR-009, FR-019,
  SC-004).
- All three are `String`. This is forced, not stylistic: an ArchUnit rule forbids the `logging`
  package from depending on Spring HTTP, Micrometer or Jackson types, so no `URI` or attribute
  object may cross into this record (`research.md` §8).
- Neither URI component ever contains a scheme, host, port, userinfo or query string (FR-005).

**Binary compatibility**: adding record components changes the canonical constructor signature.
That would be a japicmp finding against a published baseline — but 1.0.0 was never released and
there are no consumers, so the gate is legitimately skipped and no bump is owed
(`research.md` §10).

**Send-time projection**: the `outbound-request` entry is the same record with `httpStatusCode`,
`httpStatusGroup`, `responseCode` and `message` not yet known. No second record type is
introduced — one shape, two emissions.

---

## 2. Documented literals

One table, because these are the feature's whole vocabulary and every one of them is a value an
operator will see on a dashboard.

| Literal | Used for | Why this value |
|---|---|---|
| `unknown` | Either URI dimension could not be determined at all — no inbound request in scope, thread hand-off, reactive path, or an unresolvable destination | Reuses the starter's existing convention for a name it cannot determine (already the counter's null-tag fallback) |
| `unresolved` | **Metric tag only**: a URI whose template form could not be determined, where the log entry carries the raw path | Distinct from `unknown` so an operator can tell "we could not template this" from "there was no URI here" (FR-004) |
| `undefined` | Operation absent, blank, outside the shape bound, or displaced by the distinct-value cap | Fixed by the feature description; the single value for every not-a-real-operation case, so the dimension is never sometimes-absent (FR-019, FR-022) |

**Deliberate collapse**: `undefined` does not distinguish "no header sent" from "header rejected".
Splitting them would add a fourth literal and a second tag value for what an operator acts on
identically. FR-022 requires one value in the metric tag and both log entries.

---

## 3. `OperationResolver` and its admission set

The only stateful component in the feature.

**Resolution order** — first match wins, evaluated per call:

1. Header `X-Operation` read with `getFirst` → a repeated header yields one deterministic value
   (FR-025). Absent, empty or blank → `undefined`.
2. **Shape bound** (FR-020): length ≤ 64 and every character in `[A-Za-z0-9._-]`. Otherwise
   `undefined`. Implemented as a character scan, not a regex — caller-controlled input should not
   meet a backtracking engine (`research.md` §6).
3. **Admission set** (FR-021): already admitted → use it. Otherwise admit only while
   `size() < 100`; past that, `undefined`.

**Admission set properties**

| Property | Value |
|---|---|
| Scope | One running process; not shared, not persisted, lost on restart |
| Capacity | 100 distinct values (excludes `undefined`, which is not admitted) |
| Policy | First-come-first-served. **No eviction, no LRU, no ranking by volume** — FR-021 forbids it, because a dimension whose membership shifts under load changes a dashboard's meaning mid-incident |
| Bound | 100 × ≤64 chars ≈ a few KB — well inside Principle VI |
| Concurrency | Concurrent reads and admissions; benign race on the size check may admit a small number beyond 100, which is acceptable — the requirement is boundedness, not an exact ceiling |

**Non-goal**: the resolver never writes, removes or rewrites the header. A value it rejects for
telemetry still reaches the destination exactly as the calling code set it (FR-024).

---

## 4. URI resolution

Two resolvers, one output shape: a path string that is a template when obtainable, a raw path when
not, and `unknown` when neither.

### `DestinationUriResolver`

| Input available | Log entries | Metric tag |
|---|---|---|
| Template known (captured on blocking path, request attribute on reactive path) | template path, e.g. `/accounts/{id}/transfers` | same |
| Template unknown, URI known | raw path, e.g. `/accounts/12345/transfers` | `unresolved` |
| Neither | `unknown` | `unknown` |

The asymmetry in row 2 is the whole point of FR-004: detail is free in a log line and ruinous in a
metric tag. It is also why the resolver must expose the two surfaces separately rather than
returning one string.

**Path extraction from a template**: a template is a `String` that may be relative or absolute and
contains `{}`, which makes it **not a legal URI** — `URI.create` on it can throw. Since FR-037
forbids throwing, extraction is done by string scanning (find the authority, take what follows),
never by URI parsing.

### `InboundUriResolver`

Reads `RequestContextHolder.getRequestAttributes()`, then the request-scoped attribute
`org.springframework.web.servlet.HandlerMapping.bestMatchingPattern`. Returns `unknown` when the
holder is empty — which is exactly what a pooled or async thread sees, since the holder is a
non-inheritable `ThreadLocal` the servlet clears at request end. That is why FR-007's
"never report a stale value" needs no extra machinery (`research.md` §3).

Both the attribute key and the WebClient template key are **literal strings**, not constants read
off `HandlerMapping` or `DefaultWebClient`, so neither `spring-webmvc` nor `jakarta.servlet-api`
becomes a dependency (Principle II).

---

## 5. Latency observation

| Aspect | Decision |
|---|---|
| Clock | `System.nanoTime()` — monotonic, immune to wall-clock adjustment. The record's `timestamp` keeps using `Instant.now()`; the two serve different jobs and both are retained |
| Span | From immediately before dispatch to response availability or failure — includes connection acquisition and client-side queueing (FR-010) |
| Failure case | Recorded even when no response arrives (FR-016); the transport-failure path already exists and gains the observation |
| Meter | One `Timer`, `<prefix>.latency`, carrying count, total and distribution under a single tag set so percentile and share-within-boundary views come from the same series (FR-011) |

---

## 6. Configuration additions

All under the existing `service-call-logging.metrics.*` group; full detail and defaults in
[contracts/configuration.md](contracts/configuration.md).

| Key | Type | Default |
|---|---|---|
| `destination-uri-tag-name` | `String` | `destination_uri` |
| `inbound-uri-tag-name` | `String` | `inbound_uri` |
| `operation-tag-name` | `String` | `operation` |
| `latency-buckets` | `List<Duration>` | *empty → delegate to the metrics library* |

The operation **header name** is not here and never will be: it is a per-call input from the caller,
not a per-service identity (FR-018). Tag *key* names are configurable because a tag key is part of
the metric's shape in the consumer's own monitoring system — the distinction is recorded in the
spec's Assumptions.
