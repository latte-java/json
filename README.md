# Latte JSON

A small, hardened JSON serializer/deserializer for Java 25. The surface is intentionally narrow: `byte[] ⇄ Map<String, Object>`. There is no streaming API and no POJO binding.

Built for use cases like JWT payload/header processing, where the top-level value is always a JSON object.

## Requirements

- Java 25
- [Latte](https://lattejava.org) build tool

## Install

Add the dependency in `project.latte`:

```groovy
dependencies {
  group(name: "compile") {
    dependency(id: "org.lattejava:json:0.1.0")
  }
}
```

## Usage

```java
import org.lattejava.json.JSONProcessor;

JSONProcessor jp = new JSONProcessor();

// Serialize
Map<String, Object> claims = new LinkedHashMap<>();
claims.put("sub", "alice");
claims.put("exp", 1_700_000_000L);
byte[] bytes = jp.serialize(claims);

// Deserialize
Map<String, Object> parsed = jp.deserialize(bytes);
```

`JSONProcessor` instances are immutable and thread-safe — share one across threads.

### Type mapping

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

Serialization additionally accepts `Integer`, `Short`, `Byte`, `Float`, and `Double` (NaN and infinity are rejected).

### Top-level object constraint

`deserialize(byte[])` rejects any top-level value that is not a JSON object. Top-level arrays, strings, numbers, booleans, and `null` all throw `JSONProcessingException`.

### Parse-time defenses

The constructor takes configurable caps to bound parse cost:

| Parameter                | Default | Description                                                                |
|--------------------------|---------|----------------------------------------------------------------------------|
| `maxNestingDepth`        | 16      | Combined object + array depth                                              |
| `maxNumberLength`        | 1000    | Digit-run length per number (sign chars excluded)                          |
| `maxObjectMembers`       | 1000    | Members per object                                                         |
| `maxArrayElements`       | 10000   | Elements per array                                                         |
| `allowDuplicateJSONKeys` | `false` | When `false`, duplicate object keys throw                                  |

```java
JSONProcessor jp = new JSONProcessor(8, 100, 50, 100, false);
```

## Build

```
latte build      # compile + jar
latte test       # run the TestNG suite
latte clean      # remove build outputs
```

Run a single test:

```
latte test --test=JSONProcessorTest
```

## License

MIT
