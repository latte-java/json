# @JSONCatchAll (unknown-key capture)

**Date:** 2026-06-08
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen plus one runtime serializer helper for `@JSONCatchAll` — a single `Map<String, Object>` record component that captures every JSON key not mapped to a named component (OpenAPI `additionalProperties`). Deserialize reuses the existing `AnyObjectObserver`/`AnyArrayObserver` runtime; serialize needs a new "write any `Object`" helper. No `module-info` change.

## Problem

`@JSONCatchAll` is declared but inert ("TODO: Not implemented yet."). The deserialize-side runtime (`AnyObjectObserver`/`AnyArrayObserver`, which accumulate arbitrary JSON into `Map`/`List` of natural Java shapes) exists, but no codegen wires a catch-all component to it, and there is no way to serialize a `Map<String, Object>` of arbitrary values back out (`JSONBuilder` has only typed methods). A `Map<String, Object>` component is in fact currently *rejected* (the `Object` value type is unsupported).

## Goal

A record may declare exactly one `@JSONCatchAll Map<String, Object>` component. On deserialize, every key not matching a named component is captured into that map (as its natural Java shape); on serialize, the map's entries are written back as top-level keys after the named fields. `@JSONCatchAll` overrides `@JSON(strict=true)` for unknowns. The existing 231-test suite plus new fixtures are the acceptance gate.

```java
@JSON
public record APIResponse(String id, String status, @JSONCatchAll Map<String, Object> extras) {}
// {"id":"1","status":"ok","x":42,"y":[true,null]}  ⇄  extras = {x=42L, y=[true, null]}
```

## Non-goals

- **No non-record support.** Records only.
- **No write-time duplicate-key check.** The 2026-05-12 design suggested throwing if a catch-all key duplicates a named field's wire key. Deferred as YAGNI (it "should never happen with sensible code"); a duplicate would simply emit both keys. *(Flagged — easy to add later if wanted.)*
- **No typed catch-all values.** The value type is `Object`; values are the natural shapes below, never nested `@JSON` records.
- No `module-info` change, no public API change beyond the new runtime `any(...)` methods.

## Design

### 1. Detecting the catch-all

The processor finds the (at most one) record component annotated `@JSONCatchAll`. `CompanionView` gains `catchAll()` — the catch-all component's Java name, `""` when none. That component is **excluded** from the normal serialize/deserialize codegen (it isn't a typed field on the wire) and handled specially on both sides. It is still a record component, so it keeps its field declaration and `finish()` constructor argument.

A `@JSONCatchAll` component is exempt from the usual "unsupported `Map<String, Object>` value type" validation — it is recognized first and skipped.

### 2. Deserialize — capture

The catch-all field is pre-initialized so callbacks can accumulate into it:

```java
private Map<String, Object> extras = new java.util.LinkedHashMap<>();
```

When a catch-all is present, the observer's **`default` arms** capture instead of dropping/throwing:

- scalar callbacks (`string`/`integer`/`bigInteger`/`decimal`/`bool`): `default -> this.extras.put(key, value);`
- `nullValue`: `default -> this.extras.put(key, null);`
- `beginObject`: the fall-through (currently a throw) becomes `return new AnyObjectObserver();`, and `object(key, value)`'s `default` becomes `this.extras.put(key, value);`
- `beginArray`: the fall-through becomes `return new AnyArrayObserver();`, and `array(key, value)`'s `default` becomes `this.extras.put(key, value);`

So a nested unknown object/array is driven by the `Any*Observer` (natural-shape `LinkedHashMap`/`ArrayList`), and its finished value is `put` into the catch-all. This is the catch-all-aware variant of `defaultArm.jte` (and the `beginObject`/`beginArray` throws); when no catch-all is present, the existing lenient/strict behavior is unchanged. `finish()` passes the accumulated `this.extras`.

**Natural-shape mapping** (exactly what `AnyObjectObserver` produces): string→`String`, integer ≤18 digits→`Long`, integer >18→`BigInteger`, decimal/exponent→`BigDecimal`, boolean→`Boolean`, null→`null` (entry added), object→`LinkedHashMap<String,Object>`, array→`ArrayList<Object>`.

**Strict interaction:** because the catch-all replaces the `default` arms, `@JSON(strict=true)` no longer throws on unknown keys when a catch-all is present — they're captured. (A subtype's polymorphic discriminator key is still skipped by the parser, so it does *not* land in the catch-all.)

### 3. Serialize — spread

The catch-all component is omitted from the normal `builder(...)` loop. After the named fields, its entries are spread as top-level keys:

```java
for (var e : value.extras().entrySet()) b.any(e.getKey(), e.getValue());
```

`b.any(...)` (§4) writes each value at its natural shape. Null/omit-nulls follow the existing conventions (object members respect the type's `omitNulls`; array elements are always written). The catch-all key never appears as its own nested object — the entries are spread at the top level (`additionalProperties` semantics).

### 4. The runtime `any(...)` writer (new)

`JSONBuilder` gains `public JSONBuilder any(String key, Object value)` and `JSONArrayBuilder` gains `public JSONArrayBuilder any(Object value)`. Each dispatches on the value's runtime type to the existing typed method, recursing for containers, and throws on anything else:

- `null` → `nullValue(...)`; `String` → `string`; `Boolean` → `bool`;
- `BigInteger` → `bigInteger`; `BigDecimal`/`Double`/`Float` → `decimal`; other `Number` (`Long`/`Integer`/`Short`/`Byte`) → `integer`;
- `Map<?,?>` → build a nested `JSONBuilder`, `any` each entry, embed via `object(key, raw)`;
- `List<?>` → build a nested `JSONArrayBuilder`, `any` each element, embed via `array(key, raw)`;
- anything else → `throw new JSONProcessingException("Unsupported catch-all value type [" + value.getClass() + "]")`.

These two methods are the only runtime additions; both `JSONBuilder` and `JSONArrayBuilder` are already copied into the consumer's `<module>.internal` (they are in `HELPERS`), so no emission-list change is needed. They have their own unit tests (the natural shapes round-trip, nested object/array, the unsupported-type throw).

### 5. Validation (compile-time errors)

Reported on the offending component via `Messager.printMessage(ERROR, …)`, generating no companion:

- `@JSONCatchAll` on a component whose type is not exactly `Map<String, Object>` (erasure `java.util.Map`, key `String`, value `Object`).
- More than one `@JSONCatchAll` component on a type.
- `@JSONCatchAll` combined with `@JSONField` (any attribute) — the catch-all has no single key or representation policy, so per-field config is meaningless.

### 6. Files touched

- `src/main/java/org/lattejava/json/JSONBuilder.java` — add `any(String, Object)`.
- `src/main/java/org/lattejava/json/JSONArrayBuilder.java` — add `any(Object)`.
- `src/test/java/org/lattejava/json/tests/JSONBuilderTest.java` / `JSONArrayBuilderTest.java` — unit tests for `any`.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — `catchAll()` (the catch-all component name, `""` when none).
- `src/main/java/org/lattejava/json/jte/Component.java` — `isCatchAll()` (reads `@JSONCatchAll`).
- `src/main/jte/companion.jte` — pre-initialize the catch-all field; skip it in the builder loop; spread its entries via `any`.
- `src/main/jte/observerBody.jte` — catch-all `default` arms + `beginObject`/`beginArray` fall-throughs; skip the catch-all component from the typed callbacks.
- `src/main/jte/defaultArm.jte` — catch-all-aware default (or the catch-all default is emitted inline in `observerBody`).
- `src/main/java/org/lattejava/json/JSONProcessor.java` — detect the catch-all, exempt it from component validation, the §5 validation.

### 7. Conventions

New code follows the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/catchall/` driven through the real-`javac` `ProcessorHarness`:

- **Round-trip:** a record with named fields + a catch-all; unknown scalars, a nested unknown object, and a nested unknown array are captured into the map at their natural shapes and re-emitted as top-level keys (byte-exact where key order permits, else assert per-key).
- **Natural shapes:** assert the captured map holds `Long`/`BigInteger`/`BigDecimal`/`Boolean`/`null`/`LinkedHashMap`/`ArrayList` for the respective JSON values.
- **Strict override:** a `@JSON(strict=true)` record with a catch-all captures unknowns instead of throwing.
- **Empty catch-all:** no unknown keys → empty map → no extra keys on serialize.
- **`any(...)` unit tests:** the natural shapes + nested containers round-trip; an unsupported value type throws.
- **Composition:** a catch-all on a record that is also a polymorphic subtype — the discriminator key is not captured; other unknowns are.
- **Rejections:** wrong type (`Map<String,String>`, `HashMap`, a non-Map); two catch-alls on one type; `@JSONCatchAll` + `@JSONField`.

All existing 231 tests stay green — a type with no `@JSONCatchAll` is unchanged (`catchAll()` is `""`, the default arms keep their lenient/strict behavior).

## Risks

- **`default`-arm divergence.** The catch-all variant must replace the default arms on *every* callback consistently (scalars, null, object, array) and the `beginObject`/`beginArray` fall-throughs; a missed arm would silently drop a class of unknown value. Covered by tests that mix scalar, object, and array unknowns.
- **`any(...)` shape fidelity.** The serialize writer must reproduce exactly the shapes `AnyObjectObserver` captures; a mismatch breaks round-trip. The round-trip tests and the `any` unit tests pin this.
- **Pre-initialized field vs `finish()`.** The catch-all field is initialized inline (not left null) so callbacks accumulate; `finish()` passes it like any component. A non-catch-all record is unaffected.

## Alternatives considered

- **Deserialize-only catch-all** (capture unknowns, drop on serialize) — rejected: it loses the extras on round-trip, defeating `additionalProperties`. Full bidirectional is the point, which is why the runtime `any(...)` writer is in scope.
- **A `JSONProcessingException` write-time duplicate-key check** — deferred (Non-goals); cheap to add later but not load-bearing.
- **Serializing catch-all values faithfully regardless of `omitNulls`** — rejected for consistency: catch-all object members follow the type's `omitNulls` like every other object member (null extras are dropped under `omitNulls=true`); array elements are always written, as elsewhere.
