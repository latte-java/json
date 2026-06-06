# Polymorphism codegen

**Date:** 2026-06-06
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for OpenAPI-style polymorphic sealed `@JSON` interfaces (`@JSONTypeInfo`) with `@JSON` record subtypes (`@JSONSubtype`) — generate the polymorphic dispatcher companion, discriminator-first subtype serialization, and use of a polymorphic type as a root, field, `List`/`Set` element, `Map` value, or nested value. Pure codegen on the already-shipped polymorphism runtime and the nested-object machinery. No runtime change, no `module-info` change.

## Problem

The runtime polymorphism support is built and tested — `JSONPolymorphicObserver<T>`, the parser's two-pass scan-ahead (`parsePolymorphic` for the root; `JSONPolymorphicObserver` dispatch at every `beginObject`/`beginObject(key)`/array-element parse site), and the `@JSONTypeInfo`/`@JSONSubtype` annotation declarations. But the **processor generates nothing for it**: `@JSONTypeInfo`/`@JSONSubtype` carry "TODO: Not implemented yet.", the processor rejects non-record types (so a sealed interface never gets a companion), and no subtype emits a discriminator. So the headline OpenAPI feature the design promises is unreachable from `@JSON`.

## Goal

Generate the codegen that drives the existing polymorphism runtime:

```java
@JSON @JSONTypeInfo(property = "petType") public sealed interface Pet permits Dog, Cat {}
@JSON @JSONSubtype("Dog") public record Dog(String name, int packSize) implements Pet {}
@JSON @JSONSubtype("Cat") public record Cat(String name, int lives)    implements Pet {}
```

- `PetJSON.fromJSON(json)` returns the right subtype; `PetJSON.toJSON(pet)` dispatches on the concrete type and emits the discriminator first.
- A polymorphic type works as a **root**, a **field** (`record Owner(String name, Pet pet)`), a **`List`/`Set` element** (`List<Pet>`), a **`Map` value** (`Map<String, Pet>`), and **nested** (a subtype that itself has a polymorphic field) — composing recursively.

The existing 182-test suite plus new polymorphism fixtures are the acceptance gate.

## Non-goals

- **No sealed abstract-class bases.** Records can only `implements` interfaces, so in this records-only release the polymorphic base must be a sealed *interface*. Sealed-class bases arrive with the future non-record-classes work.
- **No cross-module hierarchies.** A subtype (or the parent) in a different module is rejected, same as nested — and since `@JSONSubtype`/`@JSONTypeInfo` are `SOURCE`-retained, a cross-module type is invisible as such anyway.
- **No discriminator on a non-string wire value.** OpenAPI discriminators are strings; the value is always emitted/read as a JSON string.
- **No runtime change, no public API change, no `module-info` change.**

## Design

### 1. How polymorphic-type *usage* composes with nested

A component whose type is a polymorphic sealed `@JSON` interface is handled **identically to a nested `@JSON` record** at the use site:

- **Deserialize:** `beginObject(key)` (or the array/map observer's `beginObject`) returns `new PetJSON()`; `object(key, value)` assigns `(Pet) value`. Because `JSONPolymorphicObserver extends JSONObjectHandler`, the parser already pattern-matches it and runs the scan-ahead dispatch — no codegen-visible difference from a nested record.
- **Serialize:** `.object("pet", v.pet() == null ? null : PetJSON.toJSON(v.pet()))` — the same raw-embed as nested, where `PetJSON.toJSON` dispatches on the concrete subtype.

So the only differences from a nested record are that the companion is a `JSONPolymorphicObserver` and its `toJSON` dispatches. The implementation **generalizes the nested predicate**: a new `TypeView.isPolymorphic()` joins `isNested()` under a combined `hasCompanion()` predicate (a type that has a generated `<X>JSON` companion you dispatch to). Every template branch and the `decl()`/validation path that currently special-cases `isNested()` switches to `hasCompanion()`; `nestedCompanion()` (computing `<pkg>.internal.<Simple>JSON`) already works for an interface element. *(Alternative — a parallel `isPolymorphic` codepath duplicating the nested dispatch/serialize plumbing — was rejected.)*

`TypeView.isPolymorphic()` is true when the type is a `DECLARED` sealed interface annotated with both `@JSON` and `@JSONTypeInfo`.

### 2. The polymorphic parent companion (new `polymorphic.jte`)

When the processor encounters a `@JSON` type that is a sealed interface with `@JSONTypeInfo`, it generates a dispatcher companion (not a record companion). It has **no fields and no per-field observer methods** — it is a router:

```java
public final class PetJSON implements JSONPolymorphicObserver<Pet> {
  @Override public String discriminatorKey() { return "petType"; }

  @Override public JSONObserver<? extends Pet> observerFor(String value) {
    return switch (value) {
      case "Dog" -> new demo.internal.DogJSON();
      case "Cat" -> new demo.internal.CatJSON();
      default -> throw new JSONProcessingException("Unknown discriminator value [" + value + "] for [petType]");
    };
  }

  public static Pet fromJSON(String json) { return new JSONParser().parsePolymorphic(json, new PetJSON()); }
  public static Pet fromJSON(byte[] json) { return new JSONParser().parsePolymorphic(json, new PetJSON()); }

  public static String toJSON(Pet value) {
    return switch (value) {
      case demo.Dog v -> demo.internal.DogJSON.toJSON(v);
      case demo.Cat v -> demo.internal.CatJSON.toJSON(v);
    };
  }

  public static byte[] toJSONBytes(Pet value) {
    return switch (value) {
      case demo.Dog v -> demo.internal.DogJSON.toJSONBytes(v);
      case demo.Cat v -> demo.internal.CatJSON.toJSONBytes(v);
    };
  }
}
```

- `toJSON`/`toJSONBytes` switch on the sealed type and are exhaustive (sealed `permits` Dog, Cat) — no `default` arm needed.
- Subtypes and their companions are referenced **fully-qualified** (consistent with the nested feature's decision — no imports, no collisions).
- The processor discovers subtypes via `TypeElement.getPermittedSubclasses()`, and each subtype's discriminator value via its `@JSONSubtype.value()` (or its simple name when empty).

A new `PolymorphicView` model carries: companion name/package, parent simple name + fully-qualified name, `discriminatorKey`, and the ordered list of `(discriminatorValue, subtypeFqn, subtypeCompanionFqn)`.

### 3. Subtype serialization — discriminator first

When the processor generates a **record companion** whose record is a polymorphic subtype (it `implements` an interface carrying `@JSONTypeInfo`), the `builder(...)` emits the discriminator pair as the **first** key:

```java
private static JSONBuilder builder(Dog value) {
  return new JSONBuilder(true)
      .string("petType", "Dog")          // discriminator first
      .string("name", value.name())
      .integer("packSize", value.packSize())
      ;
}
```

`CompanionView` gains an optional `discriminatorKey` + `discriminatorValue` (both empty for a non-polymorphic record); `companion.jte` emits the discriminator `.string(...)` line first when present.

### 4. Subtype observer — ignore the discriminator key

When `PetJSON` dispatches, the parser skips the discriminator key, so a subtype observer never sees it. But a direct `DogJSON.fromJSON(...)` (or a `@JSON(strict=true)` subtype) *would* see `petType` on the wire. To keep every parse path consistent, a polymorphic subtype's observer **accepts-and-ignores its own discriminator key**: `observerBody.jte`'s `string(...)` method gets a `case "petType" -> { /* discriminator: ignore */ }` arm (placed before the default arm, so strict mode does not throw on it). The discriminator is always a JSON string on the wire, so only `string(...)` needs the arm.

### 5. Validation (compile-time errors)

Reported via `Messager.printMessage(ERROR, …, element)` on the offending element, generating no companion:

- `@JSONTypeInfo` on a type that is not a **sealed interface** → error.
- A `@JSON` **sealed interface without `@JSONTypeInfo`** → error (an interface cannot be serialized without a discriminator strategy).
- A permitted subtype **missing `@JSON`** → error.
- Two subtypes resolving to the **same discriminator value** (after the simple-name default) → error.
- The discriminator property **colliding with a component name** on any subtype (the parser skips that key, so the field would silently never populate) → error.
- `@JSONSubtype` on a type whose implemented interfaces include **no `@JSONTypeInfo` parent** → error.
- A subtype (or parent) in a **different module** → cross-module error (inherited from nested).

### 6. Files touched

- **New** `src/main/jte/polymorphic.jte` — the dispatcher companion template.
- **New** `src/main/java/org/lattejava/json/jte/PolymorphicView.java` — model for `polymorphic.jte`.
- `src/main/java/org/lattejava/json/jte/TypeView.java` — `isPolymorphic()`, `hasCompanion()`; `decl()` uses `hasCompanion()`.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — optional `discriminatorKey`/`discriminatorValue` for subtype records.
- `src/main/jte/companion.jte` — emit discriminator-first builder line when present.
- `src/main/jte/observerBody.jte` — ignore the discriminator key in `string(...)`.
- `src/main/jte/memberCall.jte`, `arrayAppend.jte`, `arrayObserver.jte`, `mapObserver.jte` — switch their `isNested()` branch to `hasCompanion()` so polymorphic-typed fields/elements/values dispatch.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — relax the top-level "only records" guard to also admit a sealed `@JSONTypeInfo` interface (everything else still rejected); branch `generateCompanion` (sealed-`@JSONTypeInfo` interface → polymorphic companion; record → existing path); collect permitted subtypes + discriminator values; compute a subtype's own discriminator from its `@JSONTypeInfo` parent; accept polymorphic types as components (`isSupportedComponentType` gains `|| type.isPolymorphic()`); the §5 validation.

### 7. Conventions

New files follow the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in compile-time and runtime error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/poly/` (and a rejection fixture) driven through the real-`javac` `ProcessorHarness`:

- **Root round-trip:** `PetJSON.fromJSON`/`toJSON` for `Dog` and `Cat`; discriminator emitted first; round-trips byte-for-byte.
- **Discriminator last on input:** `{"name":…,"petType":"Cat"}` still dispatches.
- **Field position:** `record Owner(String name, Pet pet)` round-trips.
- **`List<Pet>`** and **`Map<String, Pet>`** round-trip, each element/value dispatching independently.
- **Nested polymorphism:** a subtype with a polymorphic field (or a `List<Pet>` inside a record) composes.
- **Custom vs default discriminator value:** `@JSONSubtype("k9")` honored; a subtype without `@JSONSubtype` uses its simple name.
- **Strict subtype ignores its discriminator:** a `@JSON(strict=true)` subtype parses its own wire form (with the discriminator present) without throwing.
- **Runtime errors:** unknown discriminator value throws "Unknown discriminator value [..]"; missing discriminator throws.
- **Rejections:** `@JSONTypeInfo` on a non-sealed type; `@JSON` sealed interface without `@JSONTypeInfo`; subtype missing `@JSON`; duplicate discriminator values; discriminator/component-name collision; `@JSONSubtype` without a `@JSONTypeInfo` parent.

All existing 182 tests stay green; the suite runs via `latte test`.

## Risks

- **Subtype/parent generation order.** `PetJSON` references `DogJSON`/`CatJSON` and vice-versa; both are generated in the same processing round and compiled together (standard multi-round annotation processing), so the cross-references resolve. Exercised by every round-trip test.
- **`toJSON` switch exhaustiveness.** Relies on the base being `sealed` with all `permits` subtypes `@JSON`. The validation (subtype-missing-`@JSON`) guarantees every permitted subtype has a companion, so the exhaustive switch always compiles.
- **Discriminator/field collision losing data.** Mitigated by the §5 compile-time collision check (the parser skips the discriminator key, so a same-named field would never populate).
- **Generalizing `isNested()` → `hasCompanion()`.** Must not change nested-record behavior. `hasCompanion()` is `isNested() || isPolymorphic()`, so for records it is exactly `isNested()`; covered by the still-green nested tests.

## Alternatives considered

- **Parallel `isPolymorphic` codepath** (separate dispatch/serialize templates for polymorphic-typed components) — duplicates the nested plumbing for no behavioral gain. Rejected in favor of the `hasCompanion()` generalization.
- **`@JSONTypeInfo` as the sole trigger** (not requiring `@JSON` on the interface) — the processor scans for `@JSON`, so the interface must carry it; `@JSONTypeInfo` configures, `@JSON` triggers. No change.
