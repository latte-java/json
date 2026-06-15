# Latte JSON

A small, hardened JSON library for Java 25, built as a **compile-time annotation processor**. Annotate a record, class, or sealed interface with `@JSON` and the processor generates a companion serializer/deserializer for it — there is no reflection and no runtime configuration.

Built for use cases like JWT and OpenAPI DTO processing, where the top-level JSON value is always an object.

## Requirements

- Java 25
- [Latte](https://lattejava.org) build tool

## Install

Add the dependency in `project.latte`:

```groovy
dependencies {
  group(name: "compile") {
    dependency(id: "org.lattejava:json:0.4.0")
  }
}
```

## Usage

Annotate a type with `@JSON` (the type must live in a named module — i.e. have a `module-info.java`):

```java
import org.lattejava.json.JSON;

@JSON
public record Claims(String sub, long exp) {}
```

The processor generates a companion named `{Type}JSON` in the type's `.internal` subpackage. Call its static methods:

```java
import demo.internal.ClaimsJSON; // generated

Claims claims = new Claims("alice", 1_700_000_000L);

String json  = ClaimsJSON.toJSON(claims);       // String
byte[] bytes = ClaimsJSON.toJSONBytes(claims);  // UTF-8 bytes

Claims a = ClaimsJSON.fromJSON(json);
Claims b = ClaimsJSON.fromJSON(bytes);
```

Records, classes (with a `@JSONConstructor` or bean accessors), and sealed `@JSONTypeInfo` interfaces (polymorphic hierarchies) are all supported.

### Annotations

| Annotation         | Applies to                  | Purpose                                                                                                                             |
|--------------------|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `@JSON`            | type                        | Marks a type for code generation. Options: `naming` (default `IDENTITY`), `omitNulls` (default `true`), `strict` (default `false`). |
| `@JSONField`       | member                      | Per-member config: `name`, `ignore`, `format`, `instant`, `readOnly`, `writeOnly`.                                                  |
| `@JSONCatchAll`    | `Map<String, Object>` field | Bucket that collects unknown JSON keys.                                                                                             |
| `@JSONConstructor` | constructor                 | The constructor to use when deserializing a non-record class.                                                                       |
| `@JSONTypeInfo`    | sealed interface            | Declares a polymorphic hierarchy; `property` names the discriminator key.                                                           |
| `@JSONSubtype`     | subtype                     | The discriminator value for a subtype.                                                                                              |

`naming` converts Java identifiers to wire keys at compile time: `IDENTITY`, `CAMEL_CASE`, `SNAKE_CASE`, `KEBAB_CASE`, or `PASCAL_CASE`. By default (`strict = false`) unknown JSON keys are ignored on deserialization; with `strict = true` they are rejected.

### Dynamic JSON values

Members typed as `Map<String, Object>`, `@JSONCatchAll` buckets, and `Object`-typed values accept arbitrary JSON, mapped to natural Java shapes:

| JSON                        | Java                                      |
|-----------------------------|-------------------------------------------|
| object                      | `LinkedHashMap<String, Object>`           |
| array                       | `ArrayList<Object>`                       |
| integer (≤ 18 digits)       | `Long`                                    |
| integer (> 18 digits)       | `BigInteger`                              |
| number with `.` or exponent | `BigDecimal`                              |
| string                      | `String`                                  |
| `true` / `false`            | `Boolean`                                 |
| `null`                      | `null`                                    |

Serializing dynamic values additionally accepts `Integer`, `Short`, `Byte`, `Float`, `Double` (NaN and infinity are rejected), `Instant`, and `UUID`.

### Top-level object constraint

Deserialization rejects any top-level value that is not a JSON object. Top-level arrays, strings, numbers, booleans, and `null` all throw `JSONProcessingException`. The library targets JWT and OpenAPI DTOs, which guarantee object envelopes.

### Parse-time defense

The parser bounds nesting depth — counted across objects and arrays together — to guard against stack exhaustion from hostile input:

| Parameter         | Default | Description                   |
|-------------------|---------|-------------------------------|
| `maxNestingDepth` | 64      | Combined object + array depth |

A non-positive value is rejected. Generated companions use the default; construct a `JSONParser(int maxNestingDepth)` directly to override it.

## Build

```
latte build      # compile + jar
latte test       # run the TestNG suite
latte clean      # remove build outputs
```

Run a single test:

```
latte test --test=JSONWriterTest
```

## License

MIT

## Performance

Benchmarked with JMH against Jackson databind and Gson; full methodology in [`benchmarks/`](benchmarks/).

- 2026-06-15T00:09:12Z · MacBook Air (Apple M4) · openjdk version "25.0.2" 2026-01-20 LTS
- Versions: latte 0.4.0, jackson 2.19.0, gson 2.14.0, JMH 1.37

### serialize (ops/sec; alloc bytes/op in parentheses — higher ops and lower alloc are better)

| Scenario | latte                | jackson               | gson                  |
|----------|----------------------|-----------------------|-----------------------|
| api      | 702,250 (1640 B/op)  | 602,511 (3048 B/op)   | 345,743 (10456 B/op)  |
| deep     | 1,221,873 (848 B/op) | 1,140,479 (1904 B/op) | 634,186 (4976 B/op)   |
| jwt      | 4,568,832 (232 B/op) | 4,069,549 (760 B/op)  | 2,387,330 (1560 B/op) |
| large    | 8,781 (110120 B/op)  | 8,990 (225407 B/op)   | 4,421 (587745 B/op)   |
| numbers  | 65,491 (13176 B/op)  | 63,743 (50949 B/op)   | 36,744 (98372 B/op)   |
| strings  | 140,574 (9992 B/op)  | 137,004 (19197 B/op)  | 50,039 (131952 B/op)  |

### deserialize (ops/sec; alloc bytes/op in parentheses — higher ops and lower alloc are better)

| Scenario | latte                 | jackson               | gson                  |
|----------|-----------------------|-----------------------|-----------------------|
| api      | 311,358 (11984 B/op)  | 261,740 (12736 B/op)  | 221,359 (19072 B/op)  |
| deep     | 1,502,094 (1728 B/op) | 753,220 (4016 B/op)   | 539,450 (7296 B/op)   |
| jwt      | 5,005,977 (672 B/op)  | 3,217,211 (1472 B/op) | 1,646,201 (3904 B/op) |
| large    | 7,370 (343848 B/op)   | 5,764 (344745 B/op)   | 3,105 (772466 B/op)   |
| numbers  | 29,713 (85872 B/op)   | 23,186 (97552 B/op)   | 14,811 (147728 B/op)  |
| strings  | 161,630 (21360 B/op)  | 186,747 (22112 B/op)  | 69,609 (82856 B/op)   |

