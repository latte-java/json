# @JSONField policies (ignore / readOnly / writeOnly / format / instant)

**Date:** 2026-06-07
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for the `@JSONField` representation attributes — `ignore`, `readOnly`, `writeOnly`, `format` — plus a **new `instant` attribute** (epoch-integer `Instant` representation, a new public enum) and the compile-time validation for all of them. This is **Cycle B** of the `@JSONField` + naming work (Cycle A shipped `name` + `@JSON(naming)`). It also **removes the `@JSONField.required()` attribute** — presence-checking is a caller concern, not a representation concern (see "Dropped: `required`"). No runtime change, no `module-info` change; the only public-API changes are the new `instant` attribute (+ its enum) and the `required` removal.

## Problem

`@JSONField` declares several attributes; Cycle A wired only `name`. The representation attributes (`ignore`/`readOnly`/`writeOnly`/`format`) are silently inert: a `@JSONField(ignore = true)` compiles but does nothing. No codegen reads them and no validation guards their misuse. Separately, `Instant` has only one wire form today (ISO-8601 string), but epoch-integer timestamps are a common API convention that nothing supports.

## Goal

Read and honor `ignore`, `readOnly`, `writeOnly`, `format`, and `instant` per record component, and reject contradictory combinations at compile time. The existing 219-test suite plus new fixtures are the acceptance gate; a component with no `@JSONField` (or only `name`) behaves exactly as today.

## Non-goals

- **No presence/required checking.** Removed from the design — see below.
- **No `@JSONCatchAll` interaction rules.** The catch-all codegen does not exist yet (a later cycle); its `@JSONField` interaction rules ship with it.
- **No business/value validation** (ranges, formats, cross-field) — that is Bean-Validation territory, never this library's job.
- **No non-record support.** Records only.

## Dropped: `required`

The original 2026-05-12 design specified `@JSONField(required = true)` as a deserialization-time presence check. It is **removed**: of all the `@JSONField` attributes it is the only behavioral *check* rather than a representation choice, and presence-checking is something a caller can do trivially after a parse. The implementation therefore also **deletes `@JSONField.required()`** (it was declared but never implemented), updates `AnnotationDeclarationTest`, and supersedes the `required` references in the 2026-05-12 design doc and its "Missing JSON fields" subsection. (A field missing on input keeps today's behavior: primitives stay at their Java default, references at `null`.)

## Design

### 1. The per-field policy model

`Component` reads its `@JSONField` once and exposes: `ignore()`, `readOnly()`, `writeOnly()` (booleans), `format()` (the pattern, `""` when none), and `instant()` (the `InstantFormat`, `ISO` when unset). Two derived predicates drive template filtering:

- **`serialize()`** — appears in `toJSON`: `!ignore() && !writeOnly()`.
- **`deserialize()`** — appears in the observer: `!ignore() && !readOnly()`.

Every record component is still declared as a field and passed to the canonical constructor in `finish()` (a record must construct all components). A non-deserialized field (`readOnly`/`ignore`) simply stays at its Java default after a parse; a non-serialized field (`writeOnly`/`ignore`) is omitted from the wire form.

### 2. `ignore`, `readOnly`, `writeOnly` — direction filtering

- **`companion.jte` builder** emits a member call only for `serialize()` components.
- **`observerBody.jte`** emits a `case` label (in every callback) only for `deserialize()` components.
- **Field declarations** and the **`finish()` constructor args** cover **all** components, unchanged.
- **Collection scaffolding** (the `<name>ToJSON` helper and the inner `<Cap>ArrayObserver`/`<Cap>MapObserver`) is emitted for every **non-`ignore`** collection component; a helper that ends up unreferenced (e.g. the inner observer of a `readOnly` collection, or the `ToJSON` helper of a `writeOnly` collection) is a harmless unused `private` and keeps the templates simple.

A `readOnly` key arriving on input hits the observer's `default` arm — silently dropped under lenient mode, thrown under `@JSON(strict=true)` — exactly like any unknown key, which is the intended OpenAPI behavior.

### 3. `format` — custom `java.time` pattern

Valid on **`LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, and `Instant`** (the types that work with a `DateTimeFormatter` pattern). For each such component the companion gets:

```java
private static final DateTimeFormatter <name>Formatter = DateTimeFormatter.ofPattern("<pattern>");
// for Instant, with a zone so format/parse resolve:
private static final DateTimeFormatter <name>Formatter = DateTimeFormatter.ofPattern("<pattern>").withZone(ZoneOffset.UTC);
```

The component's serialize/deserialize bypass the default ISO path (`toString()` / `Conversions.to<Type>`) and use the formatter uniformly via the temporal-query form:

- **Serialize:** `.string("<wireKey>", value.<name>() == null ? null : <name>Formatter.format(value.<name>()))`.
- **Deserialize** (in the `string(...)` callback, since a formatted time is a JSON string): `case "<wireKey>" -> this.<name> = <name>Formatter.parse(value, <SimpleType>::from);` (e.g. `LocalDate::from`, `Instant::from`).

`DateTimeFormatter`, `ZoneOffset`, and the `java.time` types are all reachable via the companion's existing `import module java.base`.

### 4. `instant` — epoch-integer Instant representation

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
- **Deserialize** (in the `integer(String key, long value)` callback, since the wire form is a JSON number): `case "<wireKey>" -> this.<name> = Instant.ofEpochMilli(value);` (or `Instant.ofEpochSecond(value)`); a `null` on the wire is still handled by the existing `nullValue` arm.

Such a component is therefore **excluded** from the `string(...)` callback (it is not a string on the wire) and **added** to the `integer(...)` callback (alongside the numeric components). `ISO` instants are unchanged — string via `Conversions.toInstant`/`toString`, or via the `format` pattern when set.

### 5. Validation (compile-time errors)

Reported on the offending component via `Messager.printMessage(ERROR, …)`, generating no companion:

- `readOnly = true` **and** `writeOnly = true` together — equivalent to `ignore`, ambiguous.
- `ignore = true` combined with any of `name`/`format`/`readOnly`/`writeOnly`/`instant` — the others have no effect.
- `format` on a component whose type is not one of the five supported `java.time` types.
- `format` whose pattern is not a valid `DateTimeFormatter.ofPattern(...)` string (caught by trying it in the processor), or contains a `"` or `\` (would break the baked literal) — a clear diagnostic instead of broken generated source.
- `instant != ISO` on a component whose type is not `Instant` — the epoch representation is `Instant`-only.
- `instant != ISO` **and** a non-empty `format` together — the field can't be both a JSON integer (epoch) and a JSON string (pattern).

### 6. Files touched

- **New** `src/main/java/org/lattejava/json/InstantFormat.java` — the `ISO`/`EPOCH_SECONDS`/`EPOCH_MILLIS` enum (public, in the exported package, alongside `NamingStrategy`).
- `src/main/java/org/lattejava/json/JSONField.java` — add `InstantFormat instant() default InstantFormat.ISO;`; **remove** `required()`.
- `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java` — drop the `required` default assertion, add the `instant` default.
- `src/main/java/org/lattejava/json/jte/Component.java` — read `@JSONField` into `ignore`/`readOnly`/`writeOnly`/`format`/`instant`; expose `serialize()`/`deserialize()`/`format()`/`instant()` and the formatter facts (`isFormatted()`, `formatterField()`, the temporal-query type name).
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — convenience views as needed, or the templates filter `components()` inline.
- `src/main/jte/companion.jte` — emit formatter fields; filter builder member calls by `serialize()`; route `format` components through the formatter and epoch-`instant` components through `.integer(... epoch ...)`.
- `src/main/jte/observerBody.jte` — filter `case` labels by `deserialize()`; route `format` components through the formatter in `string(...)`, exclude epoch-`instant` components from `string(...)` and add them to `integer(...)`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — the §5 validation (in `validateComponents`).
- `docs/design/2026-05-12-serialization.md` — supersede the `required` references.

### 7. Conventions

New code follows the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in compile-time error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/policies/` driven through the real-`javac` `ProcessorHarness`:

- **`ignore`:** a field omitted from both directions — absent from `toJSON`, and an incoming key for it is ignored (stays Java default).
- **`readOnly`:** serialized but not deserialized — present in `toJSON`; an incoming value is dropped (lenient) / thrown (strict); the field stays default after a parse.
- **`writeOnly`:** deserialized but not serialized — absent from `toJSON`; an incoming value populates the field.
- **`format`:** each of the five types round-trips with a custom pattern (e.g. `LocalDate` `"MM/dd/yyyy"`, `Instant` with a zoned pattern); the wire form uses the pattern, not ISO.
- **`instant`:** an `Instant` field with `EPOCH_SECONDS` and one with `EPOCH_MILLIS` round-trip as JSON **integers** (not strings), with the correct unit; a default (`ISO`) `Instant` is unchanged.
- **Rejections:** `readOnly`+`writeOnly`; `ignore`+another attribute; `format` on `Duration`/non-time; an invalid/`"`-containing pattern; `instant` on a non-`Instant` field; `instant`+`format` together.
- **Composition:** `readOnly`/`format` combined with `@JSON(naming)` (wire key from Cycle A, direction/format from Cycle B).

All existing tests stay green (modulo the `AnnotationDeclarationTest` `required`→`instant` swap) — a component with no `@JSONField` (or only `name`) is unchanged (`serialize()` and `deserialize()` both true, not formatted, `instant == ISO`).

## Risks

- **Formatted-field path divergence.** A formatted component must bypass the default string-form serialize/deserialize on *both* sides consistently; a one-sided miss would not round-trip. Every `format` test asserts a full byte-exact round-trip.
- **Epoch-`instant` path divergence.** Same risk for the integer path: serialize via `.integer(...)`, deserialize via the `integer(...)` callback, excluded from `string(...)`. Covered by byte-exact round-trips.
- **Unused collection scaffolding.** Generating the inner observer / `ToJSON` helper for a one-directional collection leaves an unused `private` member; harmless (javac does not error on unused privates), and it keeps direction filtering at the call sites only.
- **Pattern as a baked literal.** Mitigated by validating the pattern compiles and rejecting `"`/`\`, mirroring the Cycle A wire-key character check.

## Alternatives considered

- **Keeping `required`** — rejected (see "Dropped: `required`"): it is presence-checking, not representation, and the only behavioral throw among the attributes; a caller can check presence after a parse.
- **`instant` as a boolean** (`instant = true`) — rejected: a boolean fixes one epoch unit forever. The tri-state `InstantFormat` enum (`ISO`/`EPOCH_SECONDS`/`EPOCH_MILLIS`) makes both units selectable and keeps `ISO` as the no-op default. `format` (string patterns) and `instant` (integer epoch) are deliberately separate attributes because they're different wire shapes; setting both is the §5 conflict error.
- **Excluding `Instant` from `format`** — considered (it needs a zoned formatter), but included using `DateTimeFormatter.ofPattern(...).withZone(ZoneOffset.UTC)` and the `Instant::from` query.
- **Filtering collection scaffolding by direction** (not just `ignore`) — rejected as needless complexity; an unused `private` helper is harmless.
