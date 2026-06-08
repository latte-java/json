# Key naming & rename (@JSON naming + @JSONField name)

**Date:** 2026-06-07
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for `@JSON(naming = …)` class-wide naming strategies and `@JSONField(name = …)` per-field rename. Introduces a compile-time **wire key** distinct from the Java component name. Pure codegen change — no runtime change, no `module-info` change. This is **Cycle A** of the `@JSONField` + naming work; the remaining `@JSONField` attributes (`ignore`/`required`/`readOnly`/`writeOnly`/`format`) are **Cycle B**.

## Problem

Today the generated companions use each record component's Java name **verbatim** as the JSON key — serialization emits `.string("userName", value.userName())` and deserialization matches `case "userName"`. The `@JSON(naming)` attribute and the `NamingStrategy` enum are declared but unused; `@JSONField` (including `name`) is declared but entirely ignored (its Javadoc says "TODO: Not implemented yet."). So a consumer cannot map `userName` ⇄ `user_name`, nor rename a field to a wire key that isn't a legal Java identifier (`X-Request-ID`).

## Goal

Compute a **wire key** for every component at compile time and bake it as a string literal into the generated serialize/deserialize code:

```java
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record User(String userName, @JSONField(name = "X-Request-ID") String requestId) {}
// wire form: {"user_name":"…","X-Request-ID":"…"}
```

The existing 203-test suite plus new naming fixtures are the acceptance gate. `IDENTITY` (the default) reproduces today's output exactly.

## Non-goals

- **No other `@JSONField` attributes.** `ignore`/`required`/`readOnly`/`writeOnly`/`format` are Cycle B. This cycle reads only `@JSONField.name()`; a stray `@JSONField(required = true)` is silently ignored for one cycle (Cycle B completes it).
- **No naming of non-component keys.** Map entry keys (data, converted separately), nested-object keys (the nested type applies its own naming), and the polymorphic discriminator key (`@JSONTypeInfo.property`, verbatim) are unaffected.
- No runtime change, no public API change, no `module-info` change.

## Design

### 1. The wire key

A `Component` gains `wireKey()` (the JSON key) alongside the existing `name()` (the Java name — still used for the field declaration, accessor `value.<name>()`, cast, and generated helper/observer identifiers). The wire key is resolved once, at compile time:

1. If the component carries `@JSONField(name = X)` with non-empty `X` → the wire key is `X` **verbatim** (no strategy applied). This is the escape hatch for keys that are reserved Java words, contain hyphens, etc.
2. Otherwise → the enclosing type's `@JSON(naming)` strategy applied to the Java name.

The wire key is baked into the generated source as a string literal; the parser is never aware of the strategy (zero runtime cost).

### 2. Where it flows

- **Serialize** (`companion.jte` builder + `memberCall.jte` call site + collection `.array`/`.object` keys): the *key* argument becomes `c.wireKey()`; the accessor stays `value.<name>()`, and the generated `<name>ToJSON(...)` helper names stay the Java name. Example: `.string("user_name", value.userName())`, `.array("prior_addresses", value.priorAddresses() == null ? null : priorAddressesToJSON(...))`.
- **Deserialize** (`observerBody.jte`): every callback's `case` label becomes `c.wireKey()`; the field reference stays `this.<name>` and the constructor args stay the Java names. Example: `case "user_name" -> this.userName = value;`.
- **Unaffected:** the inner Map/Array observer class names (`cap(name) + "MapObserver"`) and the `<name>ToJSON` helper method names are Java identifiers, not wire keys — they keep using `name()`. Map entry keys, nested-type keys, and the discriminator key are out of scope (Non-goals).

Naming composes cleanly with nested objects and polymorphism: a parent's component key uses the **parent's** naming; a nested `@JSON` record's internal keys use the **nested type's own** naming (via its own companion); a polymorphic subtype's component keys use the **subtype's own** naming, while its discriminator key stays verbatim.

### 3. Word-splitting algorithm (acronym-aware standard)

A build-time utility `NamingStrategies` (public final class in the **exported** `org.lattejava.json` package — so the test module can unit-test it, as the old `Template` class did; `org.lattejava.json.jte` is not exported) exposes `public static String apply(NamingStrategy strategy, String javaName)`. Build-time only — never a runtime helper, never in `HELPERS`, and not matched by `project.latte`'s copy patterns, so it is never emitted into a consumer.

For `IDENTITY`, the Java name is returned unchanged. For all others, the Java name is split into words, each word lowercased, then re-joined per the strategy.

**Split** (`splitWords`): walking the identifier, a boundary is inserted before index `i` (`i ≥ 1`) when either:
- `c[i]` is uppercase and `c[i-1]` is lowercase or a digit — the camelCase boundary (`userName` → `user|Name`); or
- `c[i]` is uppercase, `c[i-1]` is uppercase, and `c[i+1]` exists and is lowercase — the acronym-to-word boundary (`HTTPStatus` → `HTTP|Status`).

Digits attach to the preceding word (no boundary at letter→digit). Worked examples:

| Java name | words | SNAKE | KEBAB | PASCAL | CAMEL |
|---|---|---|---|---|---|
| `userName` | user, Name | `user_name` | `user-name` | `UserName` | `userName` |
| `userID` | user, ID | `user_id` | `user-id` | `UserId` | `userId` |
| `parseHTTPResponse` | parse, HTTP, Response | `parse_http_response` | `parse-http-response` | `ParseHttpResponse` | `parseHttpResponse` |
| `packSize2` | pack, Size2 | `pack_size2` | `pack-size2` | `PackSize2` | `packSize2` |
| `name` | name | `name` | `name` | `Name` | `name` |

**Join:** `SNAKE_CASE` joins lowercased words with `_`; `KEBAB_CASE` with `-`; `PASCAL_CASE` capitalizes each word and concatenates; `CAMEL_CASE` lowercases the first word and capitalizes the rest. (Acronyms become title-case in PASCAL/CAMEL — `HttpResponse`, not `HTTPResponse` — per the confirmed standard behavior.)

`NamingStrategies` has its own unit tests covering each strategy and the boundary cases above (acronym runs, trailing acronym, digits, single word).

### 4. Duplicate wire keys

Two components on one type resolving to the **same** wire key — via two `@JSONField(name = "x")`, or a strategy collision (`userName` and `user_name` both → `user_name` under `SNAKE_CASE`) — would clobber each other on the wire and produce an unreachable `case` in the parser switch. This is a **compile-time error** reported on the type, naming the colliding components and the shared key. Detected in `validateComponents` by tracking the resolved wire keys in a set.

### 5. Files touched

- **New** `src/main/java/org/lattejava/json/NamingStrategies.java` — the build-time converter (exported package, for testability).
- **New** `src/test/java/org/lattejava/json/tests/processor/NamingStrategiesTest.java` — unit tests for the converter.
- `src/main/java/org/lattejava/json/jte/Component.java` — add `wireKey()`; the constructor takes the resolved `NamingStrategy` and reads the component's `@JSONField(name)`.
- `src/main/jte/companion.jte` — the builder key arguments use `c.wireKey()` (the `memberCall` `key`, and the `.array`/`.object` literals for collection components).
- `src/main/jte/observerBody.jte` — every `case` label uses `c.wireKey()`; field references stay `c.name()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — `readNaming(record)` (reads `@JSON.naming()`, default `IDENTITY`); pass the strategy when building each `Component`; the duplicate-wire-key check in `validateComponents`.

### 6. Conventions

New files follow the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in compile-time error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/naming/` driven through the real-`javac` `ProcessorHarness`:

- **Per-strategy round-trips:** a record under `SNAKE_CASE`, `KEBAB_CASE`, `PASCAL_CASE`, `CAMEL_CASE`, and `IDENTITY`, round-tripping byte-for-byte with keys in the expected convention.
- **Acronym + digit cases:** fields like `userID`, `parseHTTPResponse`-style names, and a digit-bearing name, asserting the wire keys.
- **`@JSONField(name)` override:** a hyphenated key (`X-Request-ID`) round-trips; an explicit name overrides the active strategy; `name = ""` falls back to the strategy.
- **Composition:** naming on a record with a nested `@JSON` field (parent keys named by the parent strategy, nested keys by the nested type's own strategy); naming on a polymorphic subtype (component keys named, discriminator key verbatim).
- **Rejection:** two components resolving to the same wire key → compile error naming both components and the key.
- **`NamingStrategiesTest`:** direct unit tests of `apply(...)` for every strategy and boundary case.

All existing 203 tests stay green — `IDENTITY` is the default, so untouched types produce identical output.

## Risks

- **Acronym/word-boundary surprises.** The split rules are precise and unit-tested; the worked-examples table is the contract. Users who dislike a derived key use `@JSONField(name = …)` to override.
- **Silent collision into the wrong field.** Mitigated by the §4 duplicate-wire-key compile error (the parser switch would otherwise have an unreachable/clobbering arm).
- **`@JSONField` partial read.** Reading only `name` this cycle means `required`/`ignore`/etc. are silently inert until Cycle B. Documented as a Non-goal; the window is one cycle.

## Alternatives considered

- **Runtime naming** (parser/builder consult the strategy) — rejected: the design's whole premise is zero-runtime-cost compile-time key baking; the parser stays naming-agnostic.
- **Naming logic on the `NamingStrategy` enum** (`strategy.apply(name)`) instead of a separate `NamingStrategies` utility — rejected to keep the public enum a pure value and the word-splitting logic out of the public runtime surface (it's build-time only).
