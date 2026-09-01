# Contract: `ResponseCodeExtractor` SPI — interaction with this feature

**Feature**: `specs/002-configurable-envelope-fields/spec.md`
**Created**: 2026-09-01
**Base contract**: `specs/001-outbound-http-observability/contracts/response-code-extractor-spi.md`
(interface shape, five contract rules, and default-implementation description are **unchanged
by this feature** and not repeated here).

This document states only what this feature adds on top of the existing SPI contract.

---

## The interface does not change

```java
@FunctionalInterface
public interface ResponseCodeExtractor {
    Optional<Integer> extract(byte[] bodyBytes);
}
```

Every existing implementation of this interface — including any consumer's own — continues to
compile and behave exactly as it does today. This is a direct consequence of the clarification
resolved during `/speckit-clarify`: widening this interface to also return a message was
considered and explicitly rejected, because it would be a breaking change to a public extension
point, requiring a MAJOR version bump and forcing every existing custom implementation across
every adopter to be rewritten — in direct conflict with this feature's own goal of requiring no
consumer code changes beyond configuration.

---

## Message extraction runs independently of this SPI

Whether the active `ResponseCodeExtractor` bean is the starter's own default
(`JacksonResponseCodeExtractor`) or a consumer-supplied implementation, the starter **always**
independently attempts to determine a message for the call, using the field-matching rule in
`envelope-matching.md`. A consumer who already has a custom `ResponseCodeExtractor` bean gains
message support the moment they adopt this feature's configuration — with no change to their
existing bean.

| Active `ResponseCodeExtractor` | Source of the logged code | Source of the logged message |
|---|---|---|
| Default (`JacksonResponseCodeExtractor`) | The matched combination's `code-field` (see `envelope-matching.md`) | The **same** matched combination's `message-field` |
| Consumer-supplied custom bean | Whatever the bean's `extract(bytes)` returns | Independently determined by matching the response body against the configured combinations, exactly as the default path does |

---

## Successful-value classification also runs independently of this SPI

FR-014 requires that the classification of a call as successful/unsuccessful honor whichever
successful value applies — even when the code itself came from a consumer's custom extractor.
The successful value used for classification is always the matched combination's
`successful-value` (or the built-in default, `0`, when nothing matched), determined the same way
as the message. A custom extractor supplies the raw code; it does not supply the threshold that
code is classified against.

**Known limitation**: if a custom extractor's envelope is not JSON-shaped in a way any configured
combination's `code-field` can match (the base contract's own "non-JSON protocol" example), no
combination ever matches, and classification always uses the built-in default successful value
(`0`). See `configuration.md`'s known limitation 7.

---

## Nothing here requires a new extension point

No new public interface is introduced by this feature. A consumer needing genuinely custom
message-extraction logic (for example, a nested message path) has no dedicated override for that
specifically — the same way there was never a dedicated override for anything other than the
whole `ResponseCodeExtractor`. This is a deliberate scope decision (Principle X — nothing in the
spec asked for one) and is recorded as a known limitation rather than built speculatively.
