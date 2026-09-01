# Contract: Envelope Combination Matching

**Feature**: `specs/002-configurable-envelope-fields/spec.md`
**Created**: 2026-09-01

This is the precise, testable statement of FR-004/FR-005/FR-006/FR-007 — how the starter decides
which `{code field, message field, successful value}` combination applies to a given call. It
exists as its own contract, separate from `configuration.md`, because the *ordering* and
*matching* rules are behaviour, not just a property list.

---

## The rule

For every call, the starter has an **ordered list of combinations**: the consuming service's
configured `service-call-logging.envelopes` list, in the order written, followed by one implicit
final combination — the built-in default (`codeField = "responseCode"`, `messageField =
"message"`, `successfulValue = 0`).

To determine the combination that applies to one call's response body:

1. Walk the ordered list from the first configured entry to the built-in default, in order.
2. For each entry, look up its `codeField` name as a top-level field of the response body's JSON
   object.
3. The **first** entry whose `codeField` is present and holds a JSON number is the match.
4. If no entry — including the built-in default — matches, the call's code is `absent`
   (FR-010) and its message is also `absent`, because the default's own `messageField` is looked
   up against the same non-matching body.

**A combination's `messageField` presence or absence never affects whether that combination is
selected.** Matching is decided by the code field alone. This is deliberate: an API that omits a
message field on success (a common, realistic shape) must not cause the starter to skip past the
correct combination and misclassify the call using the wrong one, or the default.

---

## Why order matters

Because matching is based purely on the shape of the response body — not on the destination, not
on any caller-supplied hint — **more than one configured combination can be structurally capable
of matching the same body** (for example, a body that happens to carry both `statusCode` and
`responseCode`, whether by genuine API design or coincidence). When that happens, the **earlier**
entry in the configured list always wins, deterministically.

**Consuming-service responsibility**: order combinations from most specific / most likely to
least, the same discipline as an ordered `case`/`if-else` chain. This is not something the starter
can detect or validate automatically — put the more specific match first.

---

## Worked examples

Given this configuration:

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
      message-field: message
    - code-field: responseCode
      message-field: responseDescription
      successful-value: 1
```

Effective ordered list (the built-in default is always appended):

| Order | `codeField` | `messageField` | `successfulValue` |
|---|---|---|---|
| 1 | `statusCode` | `message` | `0` (defaulted — not configured on this entry) |
| 2 | `responseCode` | `responseDescription` | `1` |
| 3 (built-in default) | `responseCode` | `message` | `0` |

| Response body | Matched entry | Logged `responseCode` | Logged `responseMessage` | Outcome |
|---|---|---|---|---|
| `{"statusCode": 0, "message": "OK"}` | 1 | `0` | `OK` | success |
| `{"statusCode": 7, "message": "Declined"}` | 1 | `7` | `Declined` | unsuccessful |
| `{"responseCode": 1, "responseDescription": "OK"}` | 2 | `1` | `OK` | success (entry 2's successful value is `1`) |
| `{"responseCode": 0, "responseDescription": "Failed"}` | 2 | `0` | `Failed` | unsuccessful (`0 != 1` for entry 2) |
| `{"responseCode": 3}` (entry 2 matches on `responseCode`; no `responseDescription`) | 2 | `3` | `absent` | unsuccessful |
| `{"errorCode": 500, "detail": "boom"}` (matches nothing, including the default) | — (none) | `absent` | `absent` | absent |
| `{"statusCode": 0, "responseCode": 1, "message": "OK"}` (ambiguous: entries 1 and 2 could both match) | **1** (earlier wins) | `0` | `OK` | success |

---

## Interaction with a consumer-supplied `ResponseCodeExtractor`

This matching rule runs **independently** of which `ResponseCodeExtractor` bean is wired
(Clarification, 2026-09-01 — see `response-code-extractor-spi.md`). When a consumer has replaced
extraction with their own bean:

- The **code** value is whatever the consumer's `extract(bytes)` returns — unaffected by this
  matching rule.
- The **message**, and the **successful value** used to classify that code, still come from
  running this same matching rule against the response body.
- If the consumer's envelope is not JSON-shaped at all (e.g., a binary protocol), no configured
  or default combination can ever match it; the successful value used for classification is
  always the built-in default (`0`), and the message is always `absent`. This is a known,
  documented limitation, not an error condition.

---

## Non-Intrusion guarantees (Constitution Principle I)

- Matching **never throws**. A malformed or oversized body, an unparseable list entry, or any
  other failure degrades to "no match" (§ The rule, step 4) — never to an exception reaching the
  business call.
- Matching operates on the same bounded, already-cached response-body prefix the starter reads
  for the existing `responseCode` extraction — it introduces no additional body I/O, and never
  reads past `max-body-bytes`.
