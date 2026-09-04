# Contract: Configuration Surface (spec 003 additions)

**Prefix**: `service-call-logging.*` (unchanged) | **Release**: 1.0.0 (unreleased — no bump owed)

This contract covers only what spec 003 adds or changes. Keys from specs 001 and 002 keep their
names, types, defaults and meanings — **no key is removed or renamed**, so no deprecation cycle is
owed (Principle IV).

---

## New keys

All four sit in the existing `metrics` group.

| Key | Type | Default | Effect |
|---|---|---|---|
| `service-call-logging.metrics.destination-uri-tag-name` | `String` | `destination_uri` | Tag key for the destination URI path on both the counter and the timer |
| `service-call-logging.metrics.inbound-uri-tag-name` | `String` | `inbound_uri` | Tag key for the inbound request URI path on both meters |
| `service-call-logging.metrics.operation-tag-name` | `String` | `operation` | Tag key for the business operation on both meters |
| `service-call-logging.metrics.latency-buckets` | `List<Duration>` | *empty* | Explicit latency bucket boundaries for the timer. Empty means **delegate to the metrics library's own default distribution behaviour** |

The three tag-name keys are `@NotBlank`, consistent with the existing tag-name keys. As today,
validation is enforced only when a Jakarta Validation provider is on the consumer's classpath;
without one, binding proceeds unvalidated rather than failing (Principle II).

### `latency-buckets` semantics

```yaml
service-call-logging:
  metrics:
    latency-buckets: [50ms, 200ms, 500ms, 1s, 2s]
```

| Configured as | Behaviour |
|---|---|
| Absent | Delegate to the metrics library's default latency distribution |
| Empty list | Same as absent — delegate (FR-014) |
| One or more valid durations | Those boundaries become the timer's service-level objectives; the library default is not used |
| Unparseable, negative or zero values | **Startup must not fail.** Unusable entries are discarded; if nothing usable remains, delegate as if absent (FR-014) |

Durations bind with Spring Boot's standard duration syntax (`500ms`, `2s`, `1m`). Order does not
matter to correctness.

### ⚠ Cardinality warning — read before leaving `latency-buckets` unset

Delegating the default means the library publishes a **full percentile histogram**: on the order of
**70 buckets**, multiplied by every combination of the metric's tags. With five dimensions this
grows fast:

```
20 destination URIs × 15 inbound URIs × 5 operations × 3 outcomes × 6 status groups
  = 27,000 tag combinations
  × ~70 buckets ≈ 1.9 million time series
```

Real traffic never fills the full cross product, but the order of magnitude is the point. **A
service with many endpoints, many downstream endpoints, or many operations should set an explicit
short `latency-buckets` list** — five to ten boundaries is ample for percentile and SLO views —
rather than taking the delegated default.

### ⚠ The delegated default is not owned by this starter

When `latency-buckets` is unset, the effective boundaries come from the **metrics library version
on your classpath**, not from this starter. Upgrading that library can change your buckets with no
change to this starter and no change to its version number.

- Version verified against for this release: *(recorded at release time — see README)*
- Boundaries that version produces: *(recorded at release time — see README)*
- **If you need boundaries stable across upgrades, set `latency-buckets` explicitly.** That is the
  only way to pin them.

---

## Keys deliberately NOT added

| Not a key | Why |
|---|---|
| Operation header name | It is a per-call input supplied by the caller, not a per-service identity. Fixed at `X-Operation` (FR-018). A tag *key* is configurable because it shapes the consumer's own metric store; the header the value arrives on is not |
| Operation length / character bound | Protects a shared-infrastructure dimension; a per-service override would defeat it (FR-020) |
| Operation distinct-value cap | Same reasoning (FR-021) |
| Timer meter name | Derived as `<prefix>.latency` from the existing `prefix` key — one naming knob, not two (Principle X) |
| Anything to enable/disable this feature alone | `service-call-logging.enabled=false` still disables the starter entirely; a per-dimension switch was not requested |

---

## Changed type: `ServiceCallLoggingProperties.Metrics`

Gains four components (three tag names, one bucket list). It is a public constructor-bound record,
so the canonical constructor signature changes. That would be binary-incompatible **against a
published baseline** — but 1.0.0 has never been released and there are no consumers, so no bump is
owed and japicmp stays legitimately skipped (`research.md` §10). Code constructing this record
programmatically (tests, mostly) must add the new arguments.

## Full example

```yaml
service-call-logging:
  enabled: true
  metrics:
    prefix: http.outbound.calls          # existing
    destination-tag-name: destination    # existing
    outcome-tag-name: outcome            # existing
    status-group-tag-name: http_status_group  # existing
    destination-uri-tag-name: destination_uri # new
    inbound-uri-tag-name: inbound_uri         # new
    operation-tag-name: operation             # new
    latency-buckets: [50ms, 200ms, 500ms, 1s, 2s]  # new — recommended over the default
```
