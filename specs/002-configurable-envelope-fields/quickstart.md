# Quickstart Validation Guide: Configurable Response Envelope Field Names

**Feature**: `specs/002-configurable-envelope-fields/spec.md`
**Created**: 2026-09-01

This guide validates the feature end-to-end on top of an existing integration (see
`specs/001-outbound-http-observability/quickstart.md` for the base starter setup — steps 1–2
there still apply unchanged). Each step below maps to one of this feature's user stories.

---

## Prerequisites

- Everything in the base quickstart's Prerequisites.
- A running service with the starter already validated per the base quickstart (correlation
  headers and `responseCode` logging already confirmed working).

---

## Step 1 — Configure a custom field-name pair (User Story 1)

Add to `application.yml`, pointing at a stub or real external API whose envelope reports outcomes
as `{"statusCode": 0, "message": "OK"}` instead of the default `responseCode`/`message`:

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
      message-field: message
```

Restart, make a call to that API, and grep the log:

```bash
./mvnw spring-boot:run 2>&1 | grep "outbound-call "
```

**Expected**:
```
INFO  c.b.s.logging.CallLogger - outbound-call source=my-service destination=... method=GET httpStatus=200 httpStatusGroup=2xx responseCode=0 responseMessage=OK
```

A call to a **different** destination that still returns the original `responseCode` shape (no
matching configured combination) should continue to log using the built-in default — confirming
the configuration is not accidentally global-only.

---

## Step 2 — Configure a non-default successful value (User Story 2)

Add a `successful-value` to the same or a different combination:

```yaml
service-call-logging:
  envelopes:
    - code-field: responseCode
      message-field: responseDescription
      successful-value: 1
```

Exercise three calls to that destination returning, respectively, `responseCode: 1`,
`responseCode: 0`, and `responseCode: 99`.

**Expected**: only the `responseCode: 1` call logs and counts as `success`; both `0` and `99` log
and count as `failure` — including `99`, a value never previously seen, confirming classification
is "equals the successful value, or not," not a fixed pair of known codes.

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'http_outbound_calls_total.*outcome="success"'
curl -s http://localhost:8080/actuator/prometheus | grep 'http_outbound_calls_total.*outcome="failure"'
```

---

## Step 3 — Multiple combinations for different external APIs (User Story 3)

Configure at least two combinations (as in Step 1 + Step 2 combined) and call both destinations
in the same test run, plus a third destination that matches neither.

**Expected**:
- Each destination's log entries reflect only its own matching combination's field names and
  successful value.
- The third destination's calls log using the built-in default (`responseCode`/`message`/`0`),
  unaffected by the other two configured combinations.
- If you deliberately configure two combinations that could both match the same body (e.g., a
  test body containing both `statusCode` and `responseCode`), confirm the **first** entry in your
  YAML list is the one whose field names and successful value show up in the log — see
  `contracts/envelope-matching.md` for the exact rule.

---

## Step 4 — Safe fallback (User Story 4)

Three checks, each independently verifiable:

**4a — Nothing configured.** Remove the `envelopes` key entirely (or leave it absent) and confirm
logging is byte-for-byte identical to the base quickstart's Step 5 output — no `responseMessage`
regression, no behaviour change.

**4b — Partial combination.** Configure only `code-field` for one entry:

```yaml
service-call-logging:
  envelopes:
    - code-field: statusCode
```

Call a destination returning `{"statusCode": 0, "message": "OK"}`. **Expected**: `responseCode=0
responseMessage=OK` — the message field name fell back to the built-in default (`message`)
independently of the configured code field name.

**4c — Body matches nothing.** Call a destination whose body is `{"errorCode": 500}` (matches
neither your configured combinations nor the built-in default). **Expected**: `responseCode=absent
responseMessage=absent`, no exception in the application log, and the response body still fully
readable by your own service code (confirm with a debug breakpoint or a test assertion, exactly as
in the base quickstart's non-intrusion checks).

---

## Step 5 — Confirm metrics are unaffected by the message

```bash
curl -s http://localhost:8080/actuator/prometheus | grep http_outbound_calls_total
```

**Expected**: the tag set is unchanged from the base quickstart — `destination`, `outcome`,
`http_status_group` only. There must be **no** message-derived tag or label of any kind (see
`research.md` §5 — an unbounded-cardinality tag would be a serious regression, not a feature).

---

## Step 6 — Confirm a pre-existing custom `ResponseCodeExtractor` still works, and now also gets a message

If you have already registered a custom `ResponseCodeExtractor` bean (per the base quickstart's
Step 8b), do not change it. Add an `envelopes` entry whose `message-field` matches a field your
custom envelope actually has, and re-run a call.

**Expected**: `responseCode` still comes from your custom bean, completely unchanged; the log line
now also shows `responseMessage` populated from the configured field — with zero changes to your
existing extractor bean. See `contracts/response-code-extractor-spi.md` for why this works.

---

## Known limitations to validate explicitly

Refer to `contracts/configuration.md` for the full list added by this feature. In addition to the
base starter's known limitations:

6. **Order-sensitivity** — validated in Step 3's last bullet.
7. **Non-field-based custom extractors don't get configurable successful-value classification** —
   if your custom extractor decodes a non-JSON protocol, confirm classification still uses `0` as
   the successful value regardless of what `successful-value` you configured, unless the response
   body coincidentally also satisfies a configured `code-field`.
8. **Unbounded message logging** — confirm a deliberately long or unusual message string from a
   test stub appears in full in the log line, with no truncation.
