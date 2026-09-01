# Contract: `ResponseCodeExtractor` SPI

**Feature**: `specs/001-outbound-http-observability/spec.md`
**Created**: 2026-08-27

---

## Interface

```java
package com.bookit.servicecalllogging;

import java.util.Optional;

@FunctionalInterface
public interface ResponseCodeExtractor {

    /**
     * Extracts the integer responseCode from the raw response body bytes.
     *
     * @param bodyBytes the raw response body, up to max-body-bytes in length; never null
     * @return Optional.of(responseCode) if successfully extracted; Optional.empty() otherwise
     */
    Optional<Integer> extract(byte[] bodyBytes);
}
```

---

## Contract Rules

Every implementation of `ResponseCodeExtractor` MUST satisfy all of the following:

### 1. Never throw

`extract(byte[] bodyBytes)` MUST return `Optional.empty()` for any input that cannot be parsed — including empty arrays, non-UTF-8 bytes, malformed JSON, JSON that lacks the `responseCode` field, or a `responseCode` value of the wrong type. It MUST NOT propagate any exception to the caller.

### 2. Return `Optional.empty()` for absence, never `null`

The return value MUST be `Optional.empty()` to signal absence. Returning `null` is a contract violation and will result in a `NullPointerException` in the instrumentation path, which may surface as a call failure — violating Constitution Principle I.

### 3. Be pure (no observable side effects)

`extract` MUST NOT write to any external system, mutate shared state, log to any framework, or perform network I/O. All work MUST be local to the method invocation.

### 4. Return within bounded time

`extract` MUST NOT block on I/O, network calls, or locks. It receives an in-memory byte array and MUST return within a time proportional to the size of `bodyBytes`. Implementations that parse large payloads inefficiently will add latency to every instrumented call — profile accordingly.

### 5. Treat `bodyBytes` as read-only

The array is shared for efficiency. Implementations MUST NOT modify the contents of `bodyBytes`.

---

## Default Implementation

The starter ships a default implementation, `JacksonResponseCodeExtractor`, which is active when:
- `jackson-databind` (i.e., `com.fasterxml.jackson.databind.ObjectMapper`) is on the classpath, AND
- No consumer-supplied `ResponseCodeExtractor` bean is registered

It reads `bodyBytes` as a JSON tree and returns the integer value of the top-level `responseCode` field. If the field is absent, null, or not an integer, it returns `Optional.empty()`.

---

## Replacing the Default

Register a bean of type `ResponseCodeExtractor` in any `@Configuration` class:

```java
@Configuration
public class MyExtractorConfig {

    @Bean
    public ResponseCodeExtractor customResponseCodeExtractor() {
        return bodyBytes -> {
            // Your parsing logic here
            return Optional.empty();
        };
    }
}
```

Because the default bean is declared `@ConditionalOnMissingBean(ResponseCodeExtractor.class)`, your bean takes precedence automatically. No other configuration change is required.

---

## Example: Custom JSON Structure

If your response envelope nests `responseCode` under a `data` key:

```json
{ "data": { "responseCode": 0, "message": "OK" } }
```

```java
@Bean
public ResponseCodeExtractor nestedResponseCodeExtractor(ObjectMapper mapper) {
    return bodyBytes -> {
        try {
            JsonNode root = mapper.readTree(bodyBytes);
            JsonNode code = root.path("data").path("responseCode");
            return code.isInt() ? Optional.of(code.intValue()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    };
}
```

---

## Example: Non-JSON Protocol

If your services use a proprietary binary format that encodes `responseCode` as the first byte:

```java
@Bean
public ResponseCodeExtractor binaryResponseCodeExtractor() {
    return bodyBytes -> {
        if (bodyBytes.length == 0) return Optional.empty();
        return Optional.of(Byte.toUnsignedInt(bodyBytes[0]));
    };
}
```

---

## Outcome Mapping

The integer returned by `extract` maps to `Outcome` as follows:

| Returned value | `Outcome` | `outcome` tag |
|---|---|---|
| `Optional.of(0)` | `SUCCESS` | `"success"` |
| `Optional.of(n)` where `n != 0` | `FAILURE` | `"failure"` |
| `Optional.empty()` | `ABSENT` | `"absent"` |

The raw integer value is also logged regardless of its outcome classification, allowing consumers to correlate log entries with specific business error codes.
