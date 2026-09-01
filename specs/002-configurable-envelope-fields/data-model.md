# Data Model: Configurable Response Envelope Field Names

**Feature**: `specs/002-configurable-envelope-fields/spec.md`
**Created**: 2026-09-01

This extends the data model in `specs/001-outbound-http-observability/data-model.md`. Only new
or changed entities are shown here; entities not listed (`Outcome`, `DestinationNameResolver`,
etc.) are unchanged.

---

## `ServiceCallLoggingProperties` (changed)

Adds one new top-level field to the existing record.

| Field | Type | Default | Constraint |
|---|---|---|---|
| `envelopes` | `List<Envelope>` (new nested record) | empty list | — |

**New nested record `Envelope`**:

| Field | Type | Default | Constraint |
|---|---|---|---|
| `codeField` | `String` | `"responseCode"` | `@NotBlank` |
| `messageField` | `String` | `"message"` | `@NotBlank` |
| `successfulValue` | `int` | `0` | — |

**Notes**:
- Constructor-bound record, `@DefaultValue`-annotated per field, exactly like the existing
  `Metrics` nested record — this is what gives FR-006 (per-field fallback) and FR-007 (default
  successful value) their behaviour without extra code: an entry that configures only
  `code-field` still binds `messageField = "message"` and `successfulValue = 0` via the binder.
- The list itself is not bound to any destination, path, or other identity (Clarification,
  2026-09-01) — order is the only thing that matters when more than one entry could match the
  same body (see `EnvelopeMatch` below and `contracts/envelope-matching.md`).
- An empty (or absent) `envelopes` list is a valid, common configuration — it means "use the
  built-in default combination for every call," identical to pre-feature behaviour (FR-015).

---

## `EnvelopeFieldExtractor` (new)

Internal engine, `com.bookit.servicecalllogging.extractor`. Not a public SPI — see
`research.md` §2 for why. Constructed once from the bound `Envelope` list and an `ObjectMapper`;
stateless and reusable across calls.

```
EnvelopeMatch extract(byte[] bodyBytes)
```

**Behaviour**:
1. Parse `bodyBytes` as a JSON tree. Any parse failure, empty input, or non-object root →
   `EnvelopeMatch` with `rawCode = null`, `successfulValue = 0`, `message = null` (FR-010).
2. Evaluate the configured `Envelope` list in configured order, then the built-in default
   (`codeField = "responseCode"`, `messageField = "message"`, `successfulValue = 0`) as an
   implicit final entry. The first entry whose `codeField` names a present, numeric node is the
   match.
3. From the matched entry: `rawCode` = that numeric value; `successfulValue` = that entry's
   configured (or defaulted) successful value; `message` = the string value of that entry's
   `messageField` if present and a string, else `null` (FR-011/FR-012 — independent of whether
   step 2 found a code).
4. If no entry matched at all (including the default), `rawCode = null` and `message = null`
   (both fields are looked up against the *default* entry's field names in this case, since the
   default is always the fallback — they will simply also be absent unless the default field
   names happen to be present).

**Never throws** — mirrors the existing `ResponseCodeExtractor` contract's rule 1 exactly, for the
same reason: an exception here must never reach the business call.

---

## `EnvelopeMatch` (new)

Immutable result record returned by `EnvelopeFieldExtractor.extract`.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `rawCode` | `Integer` | Yes | `null` when no configured or default combination matched |
| `successfulValue` | `int` | No | From the matched entry, or `0` (the built-in default) when nothing matched |
| `message` | `String` | Yes | `null` when absent, non-string, or no combination matched |

**Validation rules**:
- `successfulValue` is meaningful for classification regardless of which `ResponseCodeExtractor`
  ultimately supplied the call's code (FR-014/FR-016) — it is not gated on `rawCode` being
  non-null.
- `message` is independent of `rawCode`: either may be present while the other is absent.

---

## `ResponseCodeExtractor` (SPI interface — unchanged)

Shape is **unchanged** from `specs/001-outbound-http-observability/data-model.md`
(`Optional<Integer> extract(byte[] bodyBytes)`). `JacksonResponseCodeExtractor`, the default
implementation, now delegates internally to `EnvelopeFieldExtractor` (`Optional.ofNullable(
envelopeFieldExtractor.extract(bodyBytes).rawCode())`) but its public contract and every existing
consumer-supplied implementation of this interface continue to compile and behave exactly as
before (FR-016, FR-018, Constitution Principle IV).

---

## `ResponseCodeResult` (changed)

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `outcome` | `Outcome` | No | Unchanged |
| `rawCode` | `Integer` | Yes | Unchanged |

**Factory methods** (changed):
- `ResponseCodeResult.of(int rawCode, int successfulValue)` — replaces the old
  `of(int rawCode)`, which hardcoded `successfulValue = 0`. Creates `SUCCESS` when
  `rawCode == successfulValue`, else `FAILURE` (FR-009). This is an internal type (not the
  protected SPI), so the factory signature is free to change pre-1.0 release.
- `ResponseCodeResult.ABSENT` — unchanged.

---

## `OutboundCallRecord` (changed)

Adds one field to the existing record, inserted next to `responseCode` since the two are always
read and logged together.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `source` | `String` | No | Unchanged |
| `destination` | `String` | No | Unchanged |
| `httpMethod` | `String` | No | Unchanged |
| `httpStatusCode` | `Integer` | Yes | Unchanged |
| `httpStatusGroup` | `String` | No | Unchanged |
| `responseCode` | `Integer` | Yes | Unchanged |
| `message` | `String` | Yes | **New.** `null` when absent (FR-012) |
| `timestamp` | `Instant` | No | Unchanged |

**Notes**:
- Still the exhaustive list of what `CallLogger` may emit (Constitution Principle VII) — pending
  the constitution amendment proposed in `research.md` §6, since `message` is a new field outside
  the currently-ratified fixed set.
- `DataHygieneArchTest` must be updated to permit this new field name once that amendment lands.

---

## State Transitions

### Per-call lifecycle (RestTemplate path) — changed steps only

```
[BufferingClientHttpResponse.peek(responseCodeExtractor)]   ← unchanged call, same cached prefix
    │
    ▼
[ResponseCodeResult candidate from rawCode, BUT classify using EnvelopeMatch.successfulValue]
    │
    ▼
[EnvelopeFieldExtractor.extract(buffered.cachedPrefixBytes())]  ← NEW: reuses the same bytes
    │                                                              peek() already read; no
    ▼                                                              additional body I/O
[EnvelopeMatch{rawCode, successfulValue, message}]
    │
    ▼
[ResponseCodeResult.of(rawCode, envelopeMatch.successfulValue())]   ← FR-009/FR-014
[OutboundCallRecord(..., responseCode, message, timestamp)]         ← FR-013
    │
    ▼
[callLogger.log(record)]          ← now also logs responseMessage
[outboundCallMetrics.record(dest, outcome, httpStatusGroup)]   ← UNCHANGED: no message tag (research.md §5)
```

The reactive (WebClient) path changes symmetrically: the existing `BoundedBodyPrefix.bytes()`
accumulator is handed to `EnvelopeFieldExtractor.extract(...)` alongside the existing call to
`responseCodeExtractor.extract(...)`, both reading the same already-copied prefix.

---

## Relationship Diagram (informal, additions only)

```
ServiceCallLoggingProperties
    ├── Metrics (nested, unchanged)
    └── Envelope (nested, NEW) — list, unbound to destination

ServiceCallLoggingAutoConfiguration
    ├── EnvelopeFieldExtractor         (NEW bean; @ConditionalOnClass(Jackson), @ConditionalOnMissingBean)
    └── JacksonResponseCodeExtractor   implements ResponseCodeExtractor, now delegates to EnvelopeFieldExtractor

OutboundCallInterceptor / OutboundCallExchangeFilter
    │ calls (independently, same cached/copied prefix bytes)
    ├──► ResponseCodeExtractor.extract(bytes)        → Optional<Integer>  (default OR consumer's own)
    └──► EnvelopeFieldExtractor.extract(bytes)        → EnvelopeMatch      (always the starter's own)
    │ builds
    ▼
OutboundCallRecord(..., message)  ──► CallLogger   (logs responseMessage=)
ResponseCodeResult.of(rawCode, envelopeMatch.successfulValue())  ──► OutboundCallMetrics (unchanged tags)
```
