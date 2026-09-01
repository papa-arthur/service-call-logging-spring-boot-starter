# Contract: Configuration Properties (updated for this feature)

**Feature**: `specs/002-configurable-envelope-fields/spec.md`
**Created**: 2026-09-01
**Supersedes for the new key**: `specs/001-outbound-http-observability/contracts/configuration.md`
(that document's other nine keys are unchanged and not repeated in full here except where needed
for context — see it for the master switch, header names, and metrics keys).

All properties are bound under the prefix `service-call-logging`, exactly as before. This feature
adds exactly **one** new key.

---

## New key: `service-call-logging.envelopes`

| Key | Type | Default | Description |
|---|---|---|---|
| `service-call-logging.envelopes` | `List<Envelope>` | empty list | Ordered list of response-envelope field-name combinations this service knows about. Not bound to any destination — see `envelope-matching.md` for how the applicable combination is chosen per call. |

Each list element (`Envelope`) has three sub-keys, all optional:

| Sub-key | Type | Default | Description |
|---|---|---|---|
| `code-field` | `String` | `responseCode` | Name of the top-level JSON field this combination reads to obtain the business outcome code. |
| `message-field` | `String` | `message` | Name of the top-level JSON field this combination reads to obtain a human-readable message. |
| `successful-value` | `int` | `0` | The value of `code-field` that this combination treats as successful. Every other numeric value observed is unsuccessful (FR-009). |

An empty or absent `envelopes` list means every call is interpreted using the built-in default
combination (`responseCode` / `message` / `0`) — identical to this starter's behaviour before
this feature existed (FR-015).

---

## Example: `application.yml` with the new key at its default (empty)

```yaml
service-call-logging:
  envelopes: []   # equivalent to omitting the key entirely
```

## Example: One custom combination (User Story 1 / 2)

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
      message-field: message
      successful-value: 0
```

## Example: Multiple combinations for a service calling several external APIs (User Story 3)

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
      message-field: message
    - code-field: responseCode
      message-field: responseDescription
      successful-value: 1
    # calls to any third API not matching either entry above fall back to the
    # built-in default (responseCode / message / 0) automatically — no third entry needed
```

## Example: Only overriding the field names, keeping the default successful value

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
      message-field: message
      # successful-value omitted → defaults to 0
```

---

## What gets logged (updated)

The fixed, exhaustive set of loggable fields grows by one — see the constitution amendment
proposed in `research.md` §6, which must be ratified before this key ships:

| Field | Meaning |
|---|---|
| `responseCode` | Unchanged: the matched combination's raw code value, or `absent` |
| `responseMessage` | **New.** The matched combination's message value, logged verbatim with no length bound, or `absent` |

---

## Known limitations (additions to the five already documented for this starter)

6. **Combination matching is order-sensitive.** If a response body happens to satisfy more than
   one configured combination's `code-field`, the earliest one in the configured list is used,
   deterministically. Order combinations from most specific to least specific.
7. **A fully custom `ResponseCodeExtractor` whose code is not sourced from a same-named JSON
   field** (for example, a non-JSON/binary protocol) does not gain configurable-successful-value
   classification from this feature unless the response body also happens to satisfy one of the
   configured combinations' `code-field`s. Classification falls back to the built-in default
   successful value (`0`) in that case.
8. **The message is logged verbatim with no length bound and no content filtering.** A downstream
   response containing a very large or sensitive message string will appear in full in the log
   line. Adopters who configure a message field against a downstream API that may echo sensitive
   content are responsible for that choice.
