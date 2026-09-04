# Quickstart / Validation Guide: spec 003

**Feature**: `003-uri-operation-latency-telemetry` | **Plan**: [plan.md](plan.md)

How to prove this feature works end to end. Scenarios map to the spec's acceptance criteria and to
the TDD order in `plan.md` → Test Strategy. No implementation code here.

---

## Prerequisites

- JDK 17, Maven 3.8+
- `mvn -q clean install` succeeds on `main` before you start
- **The Principle VII amendment must be ratified before the field-adding tasks may merge**
  (`plan.md` → Constitutional Gate Finding). Everything else can be validated without it

## Full gate run

```bash
mvn clean verify
```

Must be green, with no test skipped. Gates that will bite:

| Gate | What fails you |
|---|---|
| Non-Intrusion suite | Any new step allowing an exception into the call path |
| Auto-config matrix coverage | Anything below 100% in the autoconfigure package |
| `DataHygieneArchTest` | `logging` gaining a Spring HTTP / Micrometer / Jackson dependency; any main class carrying a credential header literal; the record's field set drifting from the permitted list |
| japicmp | Stays skipped: no published baseline to diff against (1.0.0 unreleased, no consumers), so record-component changes raise no obligation |

---

## Scenario 1 — both URI dimensions appear (US1)

```bash
mvn test -Dtest='DestinationUriResolverTest,InboundUriResolverTest,UriCardinalityTest,RestTemplateIntegrationTest'
```

Expected:

- A call made from a handler mapped at `/api/v1/payments` to a template
  `/accounts/{id}/transfers` reports `inboundUri=/api/v1/payments` and
  `destinationUri=/accounts/{id}/transfers` — the **template**, not `/accounts/12345/transfers`
- Both values appear as metric tags **and** in both log entries
- A call built from a pre-made `URI` reports the **raw path** in the log entries and
  `unresolved` in the metric tag
- A call from a scheduled task or a pooled thread reports `inboundUri=unknown` — present, never
  omitted, and **never a previous request's path**
- Many calls to untemplatable URIs collapse to **one** `unresolved` metric tag value rather than
  one series per raw path, and a fixed template inventory yields one tag value per template — this
  is the URI half of SC-009 (`UriCardinalityTest`); the operation half is Scenario 2

Check both client paths: `RestTemplateIntegrationTest` exercises template capture, and
`WebClientIntegrationTest` exercises the request-attribute route. They reach the same outcome by
different means, so a pass on one proves nothing about the other.

## Scenario 2 — the operation dimension (US2)

```bash
mvn test -Dtest='OperationResolverTest,OperationCardinalityTest'
```

Expected:

| Sent | Reported |
|---|---|
| `X-Operation: SendMoney` | `SendMoney` |
| nothing | `undefined` |
| `X-Operation:` (blank) | `undefined` |
| 65+ chars, or a character outside `[A-Za-z0-9._-]` | `undefined` |
| header twice | one deterministic value |
| the 101st distinct value this process has seen | `undefined` |

Also: the header **arrives at the destination exactly as sent**, including a value rejected for
telemetry — assert on what the stub server received. And 10 000 unique values must yield ≤ 101
distinct tag values — the operation half of SC-009 (`OperationCardinalityTest`); the URI half is
verified in Scenario 1.

## Scenario 3 — latency distribution (US3)

```bash
mvn test -Dtest='LatencyDistributionTest'
```

Expected:

- Unconfigured: buckets come from the metrics library's default distribution, and percentile and
  share-within-boundary views are derivable
- `latency-buckets: [50ms, 200ms, 1s]`: those boundaries are used instead
- Empty list, or unparseable/negative values: falls back to the delegated default and **the context
  still starts** — assert startup success explicitly, not just the bucket values
- A call failing before any response **still records** an elapsed time
- The timer publishes exactly three `outcome` tag values — `success`, `failure`, `absent` — each
  reachable and distinct, with no fourth value and none collapsed into another (FR-026, FR-027).
  This is the red test that must exist in `LatencyDistributionTest` **before** the timer is
  implemented, per Constitution Principle V — not first asserted afterward in
  `MetricsIntegrationTest`, which only re-checks that the tag additions did not disturb it
  (run under the full gate in the section above, not its own numbered scenario)

Then check cardinality by hand once, because a test will not feel it for you:

```bash
curl -s localhost:8080/actuator/prometheus | grep -c 'http_outbound_calls_latency_seconds_bucket'
```

Compare against the arithmetic in [contracts/metrics-schema.md](contracts/metrics-schema.md). If
this number startles you, that is the delegated default doing what `plan.md` → Risk R1 predicts,
and the fix is an explicit `latency-buckets` list.

## Scenario 4 — two log entries (US4)

```bash
mvn test -Dtest='TwoLogEntriesTest'
```

Expected, for one call:

- Exactly **two** telemetry entries; the first begins `outbound-request`, the second
  `outbound-req-response`
- The send-time entry carries correlation values, both URIs and the operation — and no status or
  response code
- The response entry adds the response code
- A call that fails before any response: send-time entry already emitted, response entry still
  emitted with `responseCode=absent`
- Neither telemetry entry uses the old `outbound-call` naming, **and** the
  `outbound-call-instrumentation-error` warning entry is untouched and not counted among the two

## Scenario 5 — non-intrusion (Principle I, SC-005)

```bash
mvn test -Dtest='NonIntrusionRestTemplateTest,NonIntrusionWebClientTest'
```

Make each new step throw in turn — header read, either URI resolution, cap lookup, timer record,
either log entry — and assert every time that the response is **byte-for-byte identical** to an
uninstrumented call, the body is still fully readable, and the original exception (if any)
propagates untouched. This is a merge gate; a single failure blocks the PR.

## Scenario 6 — data hygiene (SC-008)

```bash
mvn test -Dtest='UriDataHygieneTest,DataHygieneArchTest'
```

Call a URI carrying userinfo credentials **and** a token-bearing query string. Assert no log entry
and no metric tag contains any part of either — not the credential, not the token, not the host.
Passing by construction (path component only) rather than by redaction is the point.

## Scenario 7 — overhead budget (SC-007)

```bash
mvn test -Dtest='OverheadBenchmark'
```

Compare against the published **under 10 µs/call**. If the new work pushes past it, the release must
publish a **re-measured** figure — leaving the old number in the README while exceeding it is a
Principle VIII violation.

---

## Release checklist

- [ ] Principle VII amendment ratified (two maintainer approvals) — **blocks the field additions**
- [ ] `DataHygieneArchTest` permitted-field list extended, **after** ratification
- [ ] Version left at **1.0.0** — never published, no consumers, so no bump is owed; japicmp still legitimately skipped
- [ ] Pre-003 behaviour changes documented for the first adopter — log rename, doubled entry count, `CallLogger` subclass caveat, counter tag additions. Behaviour notes, not a migration note: no consumers exist to migrate
- [ ] README: four new config keys, ten logged fields (was seven), both entry prefixes, operation bound and cap, every fallback literal, the delegated-bucket caveat **with its cardinality warning**, re-measured overhead figure
- [ ] Maintainer has confirmed Risk R1 (delegated bucket default) or reversed it
