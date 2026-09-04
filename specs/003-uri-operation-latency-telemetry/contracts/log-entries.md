# Contract: Log Entries (spec 003)

**Release**: 1.0.0 (unreleased). This changes the log surface, but 1.0.0 was never published and
there are no consumers, so there is no released behaviour to break and nobody to migrate.

Before this feature, one outbound call produced **one** entry. It now produces **two**.

---

## What changed

| Pre-003 (unreleased) | This feature |
|---|---|
| One entry, `outbound-call`, written after the response arrived | Two entries: `outbound-request` at send time, `outbound-req-response` on completion |
| Seven logged fields | Ten — adds `destinationUri`, `inboundUri`, `operation` |
| `outbound-call-instrumentation-error` warning entry | **Unchanged**, same text, same trigger |

---

## Entry 1 — `outbound-request` (new, at send time)

Emitted immediately before the request is dispatched, so a call that never comes back still leaves
a trace that it was attempted — which one combined entry could never show.

```
INFO  c.b.s.logging.CallLogger - outbound-request source=my-service destination=payments-service:8080 \
      method=POST destinationUri=/accounts/{id}/transfers inboundUri=/api/v1/payments \
      operation=SendMoney
```

Carries: both correlation values, the method, both URI paths, and the operation. It does **not**
carry a status, response code or message — none of those exist yet.

## Entry 2 — `outbound-req-response` (replaces `outbound-call`)

```
INFO  c.b.s.logging.CallLogger - outbound-req-response source=my-service destination=payments-service:8080 \
      method=POST destinationUri=/accounts/{id}/transfers inboundUri=/api/v1/payments \
      operation=SendMoney httpStatus=200 httpStatusGroup=2xx responseCode=0 responseMessage=OK
```

Carries the same context as entry 1, plus the observed response code and message. Emitted even when
the call fails before any response, with `httpStatus=none`, `httpStatusGroup=network-error` and
`responseCode=absent` (FR-034).

## Entry 3 — instrumentation failure warning (unchanged)

```
WARN  c.b.s.logging.CallLogger - outbound-call-instrumentation-error source=... destination=... \
      errorType=... errorMessage=...
```

**Deliberately still named `outbound-call-instrumentation-error`.** It is not a per-call telemetry
entry, it is emitted only when the instrumentation itself fails, and it is **outside** the
"exactly two entries" guarantee (FR-030, FR-033). Removing or renaming it would delete the only
signal that telemetry had degraded.

---

## The complete logged field set — ten fields, fixed and exhaustive

| Field | Meaning | Absent-value |
|---|---|---|
| `source` | Calling service name | `unknown` |
| `destination` | Resolved destination service name | `unknown` |
| `method` | Outgoing request method | — |
| `destinationUri` | **New.** Destination URI path — template form when known, else raw path | `unknown` |
| `inboundUri` | **New.** Inbound request path pattern | `unknown` |
| `operation` | **New.** Caller-supplied `X-Operation` | `undefined` |
| `httpStatus` | Received status *(response entry only)* | `none` |
| `httpStatusGroup` | Status classification *(response entry only)* | `network-error` |
| `responseCode` | Parsed business code *(response entry only)* | `absent` |
| `responseMessage` | Parsed business message *(response entry only)* | `absent` |

**Neither URI field ever contains a scheme, host, port, embedded credential or query string**
(FR-005). This is structural: the recorded value is the path component, and userinfo lives in the
authority while the query is a separate component — so there is no redaction step that could be
forgotten. Verified adversarially by test (SC-008).

Governed by Constitution Principle VII, which must be **amended** to admit the three new fields
before this ships (`plan.md` → Constitutional Gate Finding).

---

## Pairing the two entries

The two entries for one call are paired using **the consuming service's own log-correlation
context** — its trace and span identifiers. The starter adds **no** correlation field of its own
(FR-035).

**Limitation, stated plainly:** a consumer that has not configured log correlation, and any consumer
on the reactive path (where correlation propagation is already the consuming service's
responsibility per the starter's existing documented limitation), **cannot reliably pair the two
entries under concurrency.** Both entries are still emitted with all their fields; only the pairing
is unavailable. This is the accepted cost of adding no field, not a defect awaiting a fix (FR-036).

---

## Behaviour change from the pre-003 code

Nothing is owed as a *migration*: 1.0.0 is unpublished and there are no consumers to migrate. But
anyone already running this starter from source will see the change below, and the first adopter
inherits it, so it is documented rather than left to be discovered.

| If you… | Then… |
|---|---|
| Grep or parse for `outbound-call` | **Breaks.** Match `outbound-req-response` for the completion entry. Note `outbound-call-instrumentation-error` still begins with `outbound-call`, so a naive prefix match now catches only the warning |
| Count log entries per call | **Doubles.** Budget for two INFO entries per outbound call |
| Extend `CallLogger` and override `log(...)` | **You will emit no send-time entry** until you also override `logRequest(...)`. Your override keeps working for the response entry |
| Have alerts keyed on the old entry | Re-point them at `outbound-req-response` |
| Parse fields positionally | **Breaks.** Three fields were added; parse by name |

### Log volume

Two INFO entries per outbound call instead of one. For a service making millions of outbound calls
a day this is a material increase in log volume and cost — worth checking retention and ingest
budgets before upgrading.
