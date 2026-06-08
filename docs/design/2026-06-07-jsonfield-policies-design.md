# @JSONField policies (ignore / readOnly / writeOnly / required / format / instant)

**Date:** 2026-06-07
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for the remaining `@JSONField` attributes — `ignore`, `readOnly`, `writeOnly`, `required`, `format` — plus a **new `instant` attribute** (epoch-integer `Instant` representation, a new public enum) and the compile-time validation for all of them. This is **Cycle B** of the `@JSONField` + naming work (Cycle A shipped `name` + `@JSON(naming)`). No runtime change, no `module-info` change; one public-API addition (the `instant` attribute + its enum).

## Problem

`@JSONField` is declared with six attributes; Cycle A wired only `name`. The other five are silently inert: a `@JSONField(required = true)` or `@JSONField(ignore = true)` compiles but does nothing. The design (§ "The `@JSONField` annotation") specifies each, but no codegen reads them and no validation guards their misuse. Separately, `Instant` has only one wire form today (ISO-8601 string), but epoch-integer timestamps are a common API convention that nothing supports.

## Goal

Read and honor `ignore`, `readOnly`, `writeOnly`, `required`, and `format` per record component, and reject contradictory combinations at compile time. The existing 219-test suite plus new fixtures are the acceptance gate; a component with no `@JSONField` (or only `name`) behaves exactly as today.

## Non-goals

- **No `@JSONCatchAll` interaction rules.** The design lists `name`/`required` on a catch-all as errors, but `@JSONCatchAll` codegen does not exist yet (a later cycle). Those rules ship with it.
- **No non-record support.** Records only, as everywhere else this release.
- No runtime change, no public API change, no `module-info` change.

## Design

### 1. The per-field policy model

`Component` reads its `@JSONField` once and exposes: `ignore()`, `readOnly()`, `writeOnly()`, `required()` (booleans), `format()` (the pattern, `""` when none), and `instant()` (the `InstantFormat`, `ISO` when unset). Two derived predicates drive template filtering:

- **`serialize()`** — appears in `toJSON`: `!ignore() && !writeOnly()`.
- **`deserialize()`** — appears in the observer: `!ignore() && !readOnly()`.

Every record component is still declared as a field and passed to the canonical constructor in `finish()` (a record must construct all components). A non-deserialized field (`readOnly`/`ignore`) simply stays at its Java default after a parse; a non-serialized field (`writeOnly`/`ignore`) is omitted from the wire form.

### 2. `ignore`, `readOnly`, `writeOnly` — direction filtering

- **`companion.jte` builder** emits a member call only for `serialize()` components.
- **`observerBody.jte`** emits a `case` label (in every callback) only for `deserialize()` components.
- **Field declarations** and the **`finish()` constructor args** cover **all** components, unchanged.
- **Collection scaffolding** (the `<name>ToJSON` helper and the inner `<Cap>ArrayObserver`/`<Cap>MapObserver`) is emitted for every **non-`ignore`** collection component; a helper that ends up unreferenced (e.g. the inner observer of a `readOnly` collection, or the `ToJSON` helper of a `writeOnly` collection) is a harmless unused `private` and keeps the templates simple.

A `readOnly` key arriving on input hits the observer's `default` arm — silently dropped under lenient mode, thrown under `@JSON(strict=true)` — exactly like any unknown key, which is the intended OpenAPI behavior.

### 3. `required` — presence tracking

`required` means **the key must be present on input** (an explicit `null` satisfies it; only an absent key fails). Codegen, for each required (and `deserialize()`) component:

- emits a `private boolean <name>$seen;` flag on the companion;
- sets `<name>$seen = true;` in every observer arm that assigns the component (the scalar/string callback, `nullValue`, and the collection/nested `array`/`object` callback — wherever `this.<name> = …` happens);
- checks all required flags first thing in `finish()`: `if (!<name>$seen) throw new JSONProcessingException("Missing required JSON key [<wireKey>]");`.

Tracking machinery is emitted **only** for required components (no `$seen` field otherwise). A required primitive that arrives as `null` still throws the existing "null for primitive field" error; a required primitive that is absent throws the missing-key error.

### 4. `format` — custom `java.time` pattern

Valid on **`LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, and `Instant`** (the types that work with a `DateTimeFormatter` pattern). For each such component the companion gets:

```java
private static final DateTimeFormatter <name>Formatter = DateTimeFormatter.ofPattern("<pattern>");
// for Instant, with a zone so format/parse resolve:
private static final DateTimeFormatter <name>Formatter = DateTimeFormatter.ofPattern("<pattern>").withZone(ZoneOffset.UTC);
```

The component's serialize/deserialize bypass the default ISO path (`toString()` / `Conversions.to<Type>`) and use the formatter uniformly via the temporal-query form, so all five types are handled the same way:

- **Serialize:** `.string("<wireKey>", value.<name>() == null ? null : <name>Formatter.format(value.<name>()))`.
- **Deserialize** (in the `string(...)` callback, since a formatted time is a JSON string): `case "<wireKey>" -> this.<name> = <name>Formatter.parse(value, <SimpleType>::from);` (e.g. `LocalDate::from`, `Instant::from`).

`DateTimeFormatter`, `ZoneOffset`, and the `java.time` types are all reachable via the companion's existing `import module java.base`.

### 5. `instant` — epoch-integer Instant representation

A new public enum and a new `@JSONField` attribute let an `Instant` field be carried as a JSON **integer** epoch instead of a string:

```java
package org.lattejava.json;

public enum InstantFormat {
  ISO,            // default — string (ISO-8601, or the `format` pattern if set)
  EPOCH_SECONDS,  // JSON integer, seconds since the epoch
  EPOCH_MILLIS    // JSON integer, milliseconds since the epoch
}
```

`@JSONField` gains `InstantFormat instant() default InstantFormat.ISO;`. (Enum/attribute names are easy to revise; `ISO` as the default keeps every existing `Instant` field unchanged.)

For an `Instant` component with `instant != ISO`, codegen routes through the numeric path instead of the string path:

- **Serialize:** `.integer("<wireKey>", value.<name>() == null ? null : value.<name>().toEpochMilli())` (or `.getEpochSecond()`), null-safe via the existing boxed-`integer` overload.
- **Deserialize** (in the `integer(String key, long value)` callback, since the wire form is a JSON number): `case "<wireKey>" -> this.<name> = Instant.ofEpochMilli(value);` (or `Instant.ofEpochSecond(value)`).

Such a component is therefore **excluded** from the `string(...)` callback (it is not a string on the wire) and **added** to the `integer(...)` callback (alongside the numeric components). `ISO` instants are unchanged — string via `Conversions.toInstant`/`toString`, or via the `format` pattern when set.

### 6. Validation (compile-time errors)

Reported on the offending component via `Messager.printMessage(ERROR, …)`, generating no companion:

- `readOnly = true` **and** `writeOnly = true` together — equivalent to `ignore`, ambiguous.
- `ignore = true` combined with any of `name`/`required`/`format`/`readOnly`/`writeOnly`/`instant` — the others have no effect.
- `readOnly = true` **and** `required = true` — contradictory: a serialize-only field is never read, so it can never be "present" on input.
- `format` on a component whose type is not one of the five supported `java.time` types.
- `format` whose pattern is not a valid `DateTimeFormatter.ofPattern(...)` string (caught by trying it in the processor), or contains a `"` or `\` (would break the baked literal) — a clear diagnostic instead of broken generated source.
- `instant != ISO` on a component whose type is not `Instant` — the epoch representation is `Instant`-only.
- `instant != ISO` **and** a non-empty `format` together — the field can't be both a JSON integer (epoch) and a JSON string (pattern).

### 7. Files touched

- **New** `src/main/java/org/lattejava/json/InstantFormat.java` — the `ISO`/`EPOCH_SECONDS`/`EPOCH_MILLIS` enum (public, in the exported package, alongside `NamingStrategy`).
- `src/main/java/org/lattejava/json/JSONField.java` — add `InstantFormat instant() default InstantFormat.ISO;`.
- `src/main/java/org/lattejava/json/jte/Component.java` — read `@JSONField` into `ignore`/`readOnly`/`writeOnly`/`required`/`format`/`instant`; expose `serialize()`/`deserialize()`/`required()`/`format()`/`instant()` and the formatter facts (`isFormatted()`, `formatterField()`, the temporal-query type name).
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — convenience views as needed (e.g. `requiredComponents()`), or the templates filter `components()` inline.
- `src/main/jte/companion.jte` — emit formatter fields; filter builder member calls by `serialize()`; route `format` components through the formatter and epoch-`instant` components through `.integer(... epoch ...)`.
- `src/main/jte/observerBody.jte` — filter `case` labels by `deserialize()`; route `format` components through the formatter in `string(...)`, exclude epoch-`instant` components from `string(...)` and add them to `integer(...)`; emit `<name>$seen` sets and the required-key checks in `finish()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — the §6 validation (in `validateComponents`).

### 8. Conventions

New code follows the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in compile-time and runtime error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/policies/` driven through the real-`javac` `ProcessorHarness`:

- **`ignore`:** a field omitted from both directions — absent from `toJSON`, and an incoming key for it is ignored (stays Java default).
- **`readOnly`:** serialized but not deserialized — present in `toJSON`; an incoming value is dropped (lenient) / thrown (strict); the field stays default after a parse.
- **`writeOnly`:** deserialized but not serialized — absent from `toJSON`; an incoming value populates the field.
- **`required`:** present key (incl. explicit `null`) round-trips; an absent required key throws "Missing required JSON key [..]"; tracking emitted only for required fields. A required collection/nested field too.
- **`format`:** each of the five types round-trips with a custom pattern (e.g. `LocalDate` `"MM/dd/yyyy"`, `Instant` with a zoned pattern); the wire form uses the pattern, not ISO.
- **`instant`:** an `Instant` field with `EPOCH_SECONDS` and one with `EPOCH_MILLIS` round-trip as JSON **integers** (not strings), with the correct unit; a default (`ISO`) `Instant` is unchanged.
- **Rejections:** `readOnly`+`writeOnly`; `ignore`+another attribute; `readOnly`+`required`; `format` on `Duration`/non-time; an invalid/`"`-containing pattern; `instant` on a non-`Instant` field; `instant`+`format` together.
- **Composition:** `readOnly`/`format` combined with `@JSON(naming)` (wire key from Cycle A, direction/format from Cycle B); `required` + `writeOnly` together.

All existing 219 tests stay green — a component with no `@JSONField` (or only `name`) is unchanged (`serialize()` and `deserialize()` both true, not required, not formatted).

## Risks

- **`finish()` ordering with required checks.** The required-key checks must run before the constructor call; emitting them as the first statements in `finish()` keeps it simple. Covered by the required tests.
- **Formatted-field path divergence.** A formatted component must bypass the default string-form serialize/deserialize on *both* sides consistently; a one-sided miss would not round-trip. Every `format` test asserts a full byte-exact round-trip.
- **Unused collection scaffolding.** Generating the inner observer / `ToJSON` helper for a one-directional collection leaves an unused `private` member; harmless (javac does not error on unused privates), and it keeps the direction filtering localized to the call sites.
- **Pattern as a baked literal.** Mitigated by validating the pattern compiles and rejecting `"`/`\`, mirroring the Cycle A wire-key character check.

## Alternatives considered

- **`required` as non-null** (vs key-present) — rejected: "required" is about presence on the wire (OpenAPI `required` is presence), and a nullable field that is explicitly `null` is present. Non-null validation is a different concern not in scope.
- **Excluding `Instant` from `format`** — considered (it needs a zoned formatter), but included per the chosen scope using `DateTimeFormatter.ofPattern(...).withZone(ZoneOffset.UTC)` and the `Instant::from` query.
- **`instant` as a boolean** (`instant = true`) — rejected: a boolean fixes one epoch unit forever. The tri-state `InstantFormat` enum (`ISO`/`EPOCH_SECONDS`/`EPOCH_MILLIS`) makes both units selectable and keeps `ISO` as the no-op default. `format` (string patterns) and `instant` (integer epoch) are deliberately separate attributes because they're different wire shapes; setting both is the §6 conflict error.
- **Filtering collection scaffolding by direction** (not just `ignore`) — rejected as needless complexity; an unused `private` helper is harmless and keeps direction filtering at the call sites only.
