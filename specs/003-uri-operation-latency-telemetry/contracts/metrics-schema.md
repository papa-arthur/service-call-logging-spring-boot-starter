# Contract: Metrics Schema (spec 003)

**Release**: 1.0.0 (unreleased — no bump owed) | **Registry-neutral**; Prometheus renderings shown for concreteness.

The starter publishes **two** meters after this feature. Both carry the same six tags, so an
operator can slice either by the same dimensions.

---

## Meter 1 — counter (existing, gains three tags)

| | |
|---|---|
| Name | `<prefix>.total` → default `http.outbound.calls.total` |
| Prometheus | `http_outbound_calls_total` |
| Type | Counter (monotonic) |
| Increment | Once per completed or failed outbound call |

## Meter 2 — latency timer (new)

| | |
|---|---|
| Name | `<prefix>.latency` → default `http.outbound.calls.latency` |
| Prometheus | `http_outbound_calls_latency_seconds` (+ `_bucket`, `_count`, `_sum`) |
| Type | Timer with a bucketed distribution |
| Record | Elapsed time per call, **including calls that fail before a response** (FR-016) |
| Span | Immediately before dispatch → response available or failure; includes connection acquisition and client-side queueing |

---

## Tags — identical on both meters

| Tag key (default) | Configurable | Values | Bounded by |
|---|---|---|---|
| `destination` | yes | Resolved destination service name, or `unknown` | Consumer's downstream inventory |
| `outcome` | yes | `success` \| `failure` \| `absent` | Fixed at three |
| `http_status_group` | yes | `1xx`\|`2xx`\|`3xx`\|`4xx`\|`5xx`\|`network-error` | Fixed at six |
| `destination_uri` | yes | Destination URI **path**, template form; `unresolved` when untemplatable; `unknown` when undeterminable | Consumer's route inventory + 2 literals |
| `inbound_uri` | yes | Inbound request **path** pattern; `unknown` when not established | Consumer's own route table + 1 literal |
| `operation` | yes | Admitted `X-Operation` value, else `undefined` | **≤ 101** (100 admitted + `undefined`) |

**Every tag is always present on every call.** No tag is ever omitted, so a series never appears and
disappears for the same meter (FR-009, FR-019, SC-004).

### The outcome tag is reused verbatim — three values, not two

`success`, `failure` and `absent` are exactly what the starter publishes today. The feature
description's "successful / unsuccessful" wording names the *distinction an operator needs*, not a
new label set:

- Renaming `failure` → `unsuccessful` would be the separate notion of outcome FR-027 forbids, and
  would break every dashboard already querying the published value.
- `absent` — the response code could not be determined — is **neither** a success nor a failure and
  must not be folded into either. Collapsing it would silently show undetermined calls as failures
  on exactly the dashboard the feature exists to serve.

### Why `destination_uri` carries no host

The destination service's identity already has its own tag. Recording the path alone avoids
duplicating it across two tags, avoids multiplying cardinality when one logical service resolves to
several hostnames, and makes the no-credentials guarantee structural: userinfo lives in the
authority, so it cannot reach a tag that only ever holds a path (FR-005, SC-008).

To tell the same path on two different hosts apart, read `destination_uri` together with
`destination`.

---

## Cardinality

Series ≈ `destination × outcome(3) × status_group(6) × destination_uri × inbound_uri × operation(≤101)`,
and for the timer, **× buckets**.

Three of the six factors are bounded by the starter itself (`outcome`, `http_status_group`,
`operation`). The two URI factors are bounded by the consuming service's own finite route inventory
plus the `unresolved`/`unknown` literals — which is what makes FR-004's placeholder essential: a
raw, identifier-bearing path in a metric tag would be per-request-unique and unbounded.

**See the cardinality warning in [configuration.md](configuration.md)** before leaving
`latency-buckets` unset — the delegated default multiplies all of the above by roughly 70.

**SC-009 is verified adversarially**, not asserted, by two tests — one per dimension group, because
each is owned by a different user story. `OperationCardinalityTest` issues calls carrying a unique
operation value every time and asserts the distinct operation tag-value count stays within the cap;
`UriCardinalityTest` issues calls whose URIs cannot be templated and asserts they collapse to the
single `unresolved` placeholder rather than one series per raw path. Together they cover the whole
criterion.

---

## Example queries

```promql
# Failure share of SendMoney traffic — the feature's motivating question
sum(rate(http_outbound_calls_total{operation="SendMoney",outcome="failure"}[5m]))
  / sum(rate(http_outbound_calls_total{operation="SendMoney"}[5m]))

# p99 latency, by which of my endpoints triggered the call
histogram_quantile(0.99,
  sum by (le, inbound_uri) (rate(http_outbound_calls_latency_seconds_bucket[5m])))

# Share of SendMoney calls completing within 500ms (SLO view)
sum(rate(http_outbound_calls_latency_seconds_bucket{operation="SendMoney",le="0.5"}[5m]))
  / sum(rate(http_outbound_calls_latency_seconds_count{operation="SendMoney"}[5m]))
```

---

## Change from the pre-003 code

No *migration* is owed — 1.0.0 is unpublished and there are no consumers whose dashboards could
break. Documented because anyone running this starter from source will see it, and the first
adopter inherits the result.

| Change | Impact | Action |
|---|---|---|
| Counter gains `destination_uri`, `inbound_uri`, `operation` | Series identity changes | Queries that **aggregate away** the old tags keep working unchanged. Queries asserting a complete tag set, or relying on the old series identity, would need revising (FR-029) |
| No existing tag removed or renamed | None | None |
| New timer meter | Additive | Optional to adopt |
| `absent` outcome still present | None | Unchanged |

Existing meters are **absent entirely** when the consumer has no `MeterRegistry` — unchanged. The
log entries carry all new fields regardless.
