# Dynamic `Map<String, Object>` fields (arbitrary JSON values)

**Date:** 2026-06-10
**Status:** Draft (design); pending user review
**Scope:** Annotation-processor codegen only — recognize a *named* (non-`@JSONCatchAll`) record/class member typed `Map<String, Object>` as a "dynamic map": an arbitrary JSON object nested under the member's own wire key. Deserialize reuses the existing `AnyObjectObserver`/`AnyArrayObserver` runtime; serialize reuses the existing `JSONBuilder.any(String, Object)` writer. **No new runtime code, no public API change, no `module-info` change.**

## Problem

Today a member typed `Map<String, Object>` is only legal when annotated `@JSONCatchAll` (the unknown-key bucket, spread at the top level). A *named* `Map<String, Object>` member — a field that should hold an arbitrary JSON object under its own key — is **rejected** at compile time: `validateType` runs the Map value `Object` through `isSupportedComponentType`, which returns `false`, producing `"@JSON member [name] has an unsupported Map value type [java.lang.Object]"`.

This is purely a wiring gap. The two runtime pieces needed already exist and are already exercised by `@JSONCatchAll`:

- **Read:** `AnyObjectObserver` / `AnyArrayObserver` parse an arbitrary JSON object/array into a `Map<String, Object>` / `List<Object>` of natural Java shapes.
- **Write:** `JSONBuilder.any(String key, Object value)` serializes those natural shapes back out, honoring `omitNulls` and recursing into nested maps/lists with the same setting.

## Goal

A `@JSON` type may declare any number of members typed `Map<String, Object>` that are **not** `@JSONCatchAll`. Each such member is a known wire key whose value is an arbitrary JSON object.

```java
@JSON
public record Settings(String id, Map<String, Object> prefs) {}
// {"id":"1","prefs":{"a":1,"b":{"c":true},"d":[1,"x"]}}
//   ⇄  prefs = {a=1L, b={c=true}, d=[1L, "x"]}
```

- **Deserialize:** the nested JSON object at the member's key is captured into a `Map<String, Object>` of natural Java shapes (string→`String`, integer ≤18 digits→`Long`, integer >18→`BigInteger`, decimal/exponent→`BigDecimal`, boolean→`Boolean`, null→`null`, object→`LinkedHashMap<String,Object>`, array→`ArrayList<Object>`) — exactly what `AnyObjectObserver` produces.
- **Serialize:** the member is emitted as `"<wireKey>": { ... }` — nested under its own (naming-strategy-applied) wire key, **not** spread at the top level like a catch-all. Null *entries* honor the type's `omitNulls` (dropped when `true`). A null *whole map* follows the normal field convention (omitted under `omitNulls=true`, written as `null` otherwise).

The existing test suite plus new fixtures are the acceptance gate.

## Non-goals

- **`Map<String, Object>` only.** Non-`String` keys with an `Object` value (`Map<UUID, Object>`, `Map<Integer, Object>`, etc.) stay a compile error. The `String` key matches JSON object property semantics; the dynamic part is the value.
- **No `List<Object>` or bare `Object` members.** Out of scope this cycle (the requested feature is `Map<String, Object>` only). The same machinery would extend to them later, but they are not added now.
- **No typed dynamic values.** Values are the natural `Object` shapes above, never nested `@JSON` records. A member that wants typed values keeps using `Map<String, SomeJSONType>`, which already works.
- **No new runtime helpers.** `AnyObjectObserver`, `AnyArrayObserver`, and `JSONBuilder.any` already exist (added for `@JSONCatchAll`) and are reused unchanged.
- No `module-info` change, no public API change.

## Design

### 1. Detecting a dynamic map

A member is a **dynamic map** when it is a `Map`, its key type is `String`, its value type is exactly `java.lang.Object`, and it is **not** annotated `@JSONCatchAll`. (The catch-all is detected and removed from the typed-member set earlier, so it never reaches this path.)

`TypeView` gains two predicates:

- `isObject()` — the type's fully-qualified name is `java.lang.Object`.
- `isDynamicMap()` — `isMap() && key() != null && key().isString() && value() != null && value().isObject()`.

`Component` surfaces `isDynamicMap()` for the templates (delegating to its `TypeView`).

### 2. Validation (`AbstractValidator.validateType`)

In the `isMap()` branch, before the existing "unsupported Map value type" rejection, accept the dynamic-map shape. Concretely: the `String`-key + `Object`-value case is recognized and returns `true` (valid) instead of falling through to `isSupportedComponentType(v)`, which would reject `Object`.

The existing rejections are preserved:

- A non-`String` key with `Object` value still fails the existing Map-key check (`"unsupported Map key type ..."`).
- Every other unsupported value type still fails `isSupportedComponentType` as today.
- `Map<String, Object>` annotated `@JSONCatchAll` is unaffected — it is handled by the catch-all path (validated separately, exempt from this method).

### 3. Deserialize — capture under the member's key

In `observerBody.jte`, `beginObject(String key)` currently routes a `Map` member to its specialized `<Name>MapObserver`. A **dynamic map** member instead returns the shared arbitrary-object observer:

```java
case "<wireKey>" -> { return new AnyObjectObserver(); }
```

The parser drives the nested object through `AnyObjectObserver` (natural-shape `LinkedHashMap`/`ArrayList`) and delivers the finished value to the existing `object(String key, Object value)` arm, whose condition (`c.type().isMap() || c.type().hasCompanion()`) already matches a dynamic map and assigns it:

```java
case "<wireKey>" -> this.<name> = (java.util.Map<String, Object>) value;
```

No new arm is needed on the deserialize side beyond the `beginObject` routing — the assignment arm already exists. The specialized `<Name>MapObserver` is **not** generated for dynamic maps (see §5).

### 4. Serialize — nested object under the member's key

In `companion.jte`, the `builder(...)` loop currently emits a `Map` member via `.object("<wireKey>", value.<read> == null ? null : <name>ToJSON(value.<read>))`, where `<name>ToJSON` is the specialized typed-map serializer from `mapObserver.jte`. For a dynamic map the call site is the same shape, but `<name>ToJSON` is generated to use the arbitrary-value writer:

```java
private static String <name>ToJSON(java.util.Map<String, Object> v) {
  var b = new JSONBuilder(<omitNulls>);
  for (var en : v.entrySet()) b.any(en.getKey(), en.getValue());
  return b.build();
}
```

`b.any(...)` writes each value at its natural shape and respects `<omitNulls>` (null entry dropped when `true`, nested maps/lists recurse with the same flag). The member is emitted as `"<wireKey>": { ... }` — nested under its own key. A null whole map yields the normal `omittedNull`/`null` behavior via the `value.<read> == null ? null : ...` guard, identical to typed maps.

### 5. Codegen wiring (`mapObserver.jte` / `companion.jte`)

`companion.jte` iterates `collectionComponents()` and calls `@template.mapObserver` for each `Map` member. For a dynamic map, `mapObserver.jte` emits **only** the `<name>ToJSON` serializer above and **skips** the `<Name>MapObserver` inner class (that observer exists to read *typed* values key-by-key; dynamic maps read through `AnyObjectObserver` instead). This keeps the dynamic-map path from generating dead/incorrect typed-value callbacks.

### 6. Imports

`companion.jte` currently imports `AnyArrayObserver`/`AnyObjectObserver` only when a catch-all is present (`!view.catchAll().isEmpty()`). Broaden that guard to **catch-all present OR any dynamic-map member present**, so the dynamic-map `beginObject` routing compiles. `CompanionView` gains a small predicate (e.g. `hasDynamicMap()`) for the template to test.

### 7. Coexistence

A type may freely combine dynamic-map members with a single `@JSONCatchAll`:

- The `@JSONCatchAll` still captures **unknown** keys and spreads them at the top level.
- A dynamic-map member is a **known** wire key; its nested object is captured under that key and is never confused with the catch-all (the parser dispatches by key through the `beginObject` switch before any default arm runs).

### 8. Files touched

- `src/main/java/org/lattejava/json/jte/TypeView.java` — add `isObject()` and `isDynamicMap()`.
- `src/main/java/org/lattejava/json/jte/Component.java` — surface `isDynamicMap()`.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — add `hasDynamicMap()` (any non-catch-all `Map<String,Object>` member).
- `src/main/java/org/lattejava/json/processor/AbstractValidator.java` — accept the dynamic-map shape in `validateType`'s Map branch.
- `src/main/jte/observerBody.jte` — route a dynamic-map member's `beginObject` to `new AnyObjectObserver()`.
- `src/main/jte/companion.jte` — broaden the `Any*Observer` import guard to include dynamic maps; dynamic-map serialize call site (same `.object(...)` shape, dynamic `<name>ToJSON`).
- `src/main/jte/mapObserver.jte` — for a dynamic map, emit only the `any`-based `<name>ToJSON` serializer and skip the specialized `MapObserver`.
- New fixtures under `src/test/resources/fixtures/` and a codegen test (see below).

### 9. Conventions

New code follows the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures driven through the real-`javac` processor harness, in the style of `MapCodegenTest` and the `nested/demo` fixtures:

- **Round-trip:** a type with a `Map<String, Object>` member; a nested object containing scalars, a nested object, and a nested array round-trips byte-exact (where key order permits, else per-key assertions).
- **Natural shapes:** assert the captured map holds `Long`/`BigInteger`/`BigDecimal`/`Boolean`/`null`/`LinkedHashMap`/`ArrayList` for the respective JSON values, and that insertion order is preserved.
- **`omitNulls` true vs false:** a null map entry is dropped under `omitNulls=true` and written as `null` under `omitNulls=false`; nested maps/lists inherit the same flag.
- **Whole-map-null field:** a null `Map<String, Object>` member is omitted under `omitNulls=true` and emitted as `"<wireKey>":null` under `omitNulls=false`.
- **Naming strategy:** the member is emitted under its naming-strategy-applied wire key (e.g. snake_case), and reads back from that key.
- **Coexistence:** a type with both a dynamic-map member and a `@JSONCatchAll` — the dynamic map captures its known key, the catch-all captures the remaining unknowns; both round-trip.
- **Rejections still hold:** `Map<Integer, Object>` (non-`String` key) still errors; other unsupported Map value types still error.

All existing tests stay green — a type with no dynamic-map member is unchanged (`isDynamicMap()` is `false` everywhere, `hasDynamicMap()` is `false`, the import guard and codegen paths behave exactly as before).

## Risks

- **Read/write shape fidelity.** The serialize writer (`JSONBuilder.any`) must reproduce exactly the shapes `AnyObjectObserver` captures; this already holds for `@JSONCatchAll` and is reused verbatim. The round-trip and natural-shape tests pin it for the dynamic-map path too.
- **`beginObject` routing precedence.** A dynamic map is still `isMap()`, so the new dynamic-map arm in `beginObject` must be ordered/guarded so it wins over the generic `isMap()` → `<Name>MapObserver` arm. Covered by the round-trip test (a wrong order would route to a non-existent or typed observer and fail to compile or to parse nested values).
- **Import guard.** Forgetting to broaden the `Any*Observer` import guard would break compilation only for types that have a dynamic map but no catch-all — the most common case. The coexistence test (catch-all present) would *not* catch this, so a dynamic-map-without-catch-all fixture is included specifically to exercise the guard.

## Alternatives considered

- **Extract a shared "any-value codec" abstraction first** (Approach B in brainstorming) — refactor the catch-all read/write into an explicitly shared component, then express both catch-all and dynamic maps on top of it. Rejected for this cycle: the helpers (`Any*Observer`, `JSONBuilder.any`) are already shared in practice, so the refactor adds churn and risk with no functional gain. Worth revisiting only if the "arbitrary JSON value" pattern spreads to `List<Object>`/bare `Object`.
- **Spread the dynamic map at the top level like a catch-all** — rejected: a named member is a known key and must nest under its own name; flattening would collide with sibling fields and break round-trip.
- **Allow string-form keys (`UUID`/enum/`java.time`) with `Object` values** — deferred as YAGNI; the request is `Map<String, Object>`, and JSON object keys are strings. Easy to widen later by relaxing the key check in `isDynamicMap()` and `validateType`.
