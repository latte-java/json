# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Documentation

- `docs/design/` — all design documents and specs (filenames prefixed with `YYYY-MM-DD-` creation date)
- `docs/implementation/` — all implementation plans (filenames prefixed with `YYYY-MM-DD-` creation date)

## Build system

Built with **Latte** (`project.latte`), not Maven/Gradle. Targets are invoked via the `latte` CLI from the repo root:

- `latte clean` — clean build outputs
- `latte build` — compile + JAR (no tests)
- `latte test` — runs TestNG suite (depends on `build`)
- `latte int` — local integration publish (depends on `test`)
- `latte release` — full release (depends on `clean` + `test`)
- `latte idea` — regenerate the IntelliJ `.iml`
- `latte print-dependency-tree`

Requires **Java 25** on the PATH. The `latte` CLI will tell you if it isn't.

### Running a single test

Use the `--test` flag on the `test` target:

```
latte test --test=JSONWriterTest
```

## Git worktrees

Create all git worktrees inside the project directory under `.worktrees/` (e.g. `git worktree add .worktrees/<branch-name> <branch>`). Never create worktrees in the home directory or anywhere else outside the repo.

## Architecture

This module is a **compile-time annotation processor**, not a runtime JSON library. `org.lattejava.json.JSONProcessor` implements `javax.annotation.processing.Processor` (registered via `provides` in `module-info.java`) and runs during the **consumer's** compilation. For every type annotated `@JSON` it generates a companion serializer/deserializer. The module ships **no runtime classes of its own** — the runtime is copied into each consuming module (see below). There is no reflection and no intermediate `Map`; binding is generated, observer-driven code.

### Public surface

The module `exports org.lattejava.json`, which contains:

- **Annotations** — `@JSON` (type-level; elements `naming`, `omitNulls` default `true`, `strict` default `false`), `@JSONField` (per-member: `name`, `ignore`, `format`, `instant`, `readOnly`, `writeOnly`), `@JSONCatchAll` (one `Map<String, Object>` bucket for unknown keys), `@JSONConstructor` (deserialization constructor for non-record classes), `@JSONTypeInfo` (`property` discriminator on a sealed interface) + `@JSONSubtype` (a subtype's discriminator value).
- **Enums** — `NamingStrategy` (`IDENTITY`, `CAMEL_CASE`, `SNAKE_CASE`, `KEBAB_CASE`, `PASCAL_CASE`; applied at compile time) and `InstantFormat` (`ISO`, `EPOCH_SECONDS`, `EPOCH_MILLIS`).
- **`JSONProcessor`** — the processor itself. It owns only round-level guards and a dispatch table; member discovery, validation, helper emission, and companion generation live in `org.lattejava.json.processor.*`.
- **`JSONProcessingException`** — the runtime exception used by generated code and by the parser/writer.

### Generated code

For a type `Foo`, the processor generates **`FooJSON`** (in `Foo`'s `.internal` subpackage) with this static API:

```java
String  FooJSON.toJSON(Foo value)               // → JSON String
byte[]  FooJSON.toJSONBytes(Foo value)          // → UTF-8 bytes
Foo     FooJSON.fromJSON(String json)
Foo     FooJSON.fromJSON(byte[] json)
void    FooJSON.write(JSONWriter w, Foo value)  // shared-buffer path nested companions call
```

`FooJSON implements JSONObserver<Foo>`, so deserialization is observer-driven. Records, classes (via `@JSONConstructor` or bean accessors), and sealed `@JSONTypeInfo` hierarchies are all supported. Companions are rendered from JTE templates under `src/main/jte/*.jte`.

### Runtime helpers and the `.internal` copy

The runtime — `JSONParser`, `JSONWriter`, `JSONObserver`, `JSONPlan`, `AnyObjectObserver`/`AnyArrayObserver`, `JSONPlanArrayObserver`/`JSONPlanMapObserver`, `JSONPolymorphicObserver`, `SkipObserver`/`SkipArrayObserver`, `Conversions`, `Numbers`, `JSONProcessingException` — lives as ordinary classes in `src/main/java/org/lattejava/json/`. The build (`project.latte`) copies these sources into `build/classes/main/org/lattejava/json/internal/` so they ship as resources; at the consumer's compile time `HelperEmitter` reads them, rewrites the package to `<module>.internal`, and emits one copy into the consuming module. **Edit the canonical class in `src/main/java/org/lattejava/json/` — the build re-copies it into `.internal`. Never edit a generated `.internal` copy.**

### Parser

`JSONParser` is a single-pass, observer-driven byte parser (the `String` overloads UTF-8-encode once and delegate). Keys are matched against the observer without allocation; escape-free strings are sliced in one exact-size allocation; integers accumulate during the digit scan. Diagnostic `$...` JSON paths in error messages are reconstructed lazily — nothing is tracked on the hot path; on failure the input is re-walked to the failure position.

#### Top-level-object constraint

`parse(...)` **rejects any top-level value that is not a JSON object** — top-level arrays, strings, numbers, booleans, and `null` all throw. This is intentional: the library targets OpenAPI DTOs and JWT payloads/headers (RFC 7519 §7.2 guarantees objects). Don't "fix" this by relaxing it; it's load-bearing for downstream callers.

#### Parse-time defense

The only configurable cap is **`maxNestingDepth`** (default **64**, counted across objects and arrays together, checked before recursing). The constructor rejects `maxNestingDepth <= 0` — a 0/negative cap would silently disable the defense. New caps should follow that pattern.

> The earlier `byte[] ⇄ Map` design also had `maxNumberLength` / `maxObjectMembers` / `maxArrayElements` / `allowDuplicateJSONKeys`; those are **not** present in the current parser. (`README.md` still documents the old runtime API and those caps — it is stale.)

### Thread-safety

- **`JSONParser` is NOT thread-safe** — it holds per-parse cursor state. Create a new `JSONParser` per parse call (generated companions do exactly this).
- **`JSONWriter` is thread-confined via a thread-local recycled buffer** (`acquire` → `write` → `finishString`/`finishBytes` → `release`). Buffers larger than 1 MB are not retained.

### Type mapping (dynamic / "Any" values)

Typed members deserialize straight into the target type. Arbitrary-JSON members — `Map<String, Object>`, `@JSONCatchAll`, and `Object`-typed values — map JSON to natural Java shapes:

- JSON object → `LinkedHashMap<String, Object>` (insertion-order preserving — tests assert this)
- JSON array → `ArrayList<Object>`
- JSON integer (no `.`, no `e`/`E`): `Long` if digit-run ≤ 18, else `BigInteger`. The 18-digit cutoff is deliberate (`Long.MAX_VALUE` is 19 digits) and avoids overflow checks.
- JSON number with `.` or exponent → `BigDecimal`
- string/boolean/null → `String` / `Boolean` / `null`

Serialization (`JSONWriter`, e.g. `w.any(...)` for catch-all values) accepts `String`, `Boolean`, `Integer`/`Long`/`Short`/`Byte`/`BigInteger`, `BigDecimal`, `Float`/`Double` (NaN/Infinity rejected), `Instant`, `UUID`, `Map`, `List`, and `null`. Anything else throws.

## Java module system

Both main and test source roots have a `module-info.java`. The test module is `org.lattejava.json.tests` and `opens` itself to `org.testng` for reflection. Tests use `import module java.base;` / `import module org.testng;` — JDK 25 module imports, not class imports. New code should follow the same style.

## Code conventions

Authoritative rules live in `.claude/rules/` (auto-loaded for `**/*.java`):

- `code-conventions.md` — acronyms stay uppercase (`JSONProcessor`, not `JsonProcessor`), alphabetization defaults, in-class member order, prefer module imports
- `error-messages.md` — wrap runtime values in `[brackets]` in exception messages, log lines, and `toString()` output (not single or double quotes)

Don't reintroduce title-cased acronyms or quoted error values — both are inconsistent with the existing code.
