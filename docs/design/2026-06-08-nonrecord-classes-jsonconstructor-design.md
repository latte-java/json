# Non-record classes via @JSONConstructor

**Date:** 2026-06-08
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for `@JSON` **classes** (not records) whose deserialization constructor is marked `@JSONConstructor`. Members are the constructor's parameters; per-member config (`@JSONField`, `@JSONCatchAll`) is carried **on the parameter** — this cycle adds `ElementType.PARAMETER` to both annotations' `@Target`. This is **Cycle 1** of non-record-class support; the no-arg-constructor + setter/public-field path (the JavaBean pattern) is **Cycle 2**. No runtime change, no `module-info` change; the only public-API change is the two `@Target` additions.

## Problem

The processor accepts only records (and sealed `@JSONTypeInfo` interfaces); a `@JSON` class is rejected ("@JSON supports only records and sealed @JSONTypeInfo interfaces"). The design specifies that a class with a `@JSONConstructor`-annotated constructor should deserialize via that constructor (parameter names → JSON keys) and serialize via resolved accessors (`getFoo()`/`isFoo()`/`foo()`/public field), but no codegen does this and `@JSONConstructor` carries "TODO: Not implemented yet.".

## Goal

A `@JSON` class with exactly one `@JSONConstructor` constructor round-trips:

```java
@JSON
public class Point {
  private final int x;
  private final int y;
  @JSONConstructor public Point(int x, int y) { this.x = x; this.y = y; }
  public int getX() { return x; }
  public int getY() { return y; }
}
// {"x":1,"y":2}  ⇄  new Point(1, 2)
```

The existing 242-test suite plus new fixtures are the acceptance gate. **Records are completely unaffected** — they keep their canonical-constructor / bare-accessor path.

## Key insight: an `@JSONConstructor` class ≈ a record

The deserialize half is **identical** to a record: the companion observer accumulates one field per constructor parameter and `finish()` calls the constructor — `new Point(this.x, this.y)` is exactly what a record companion already emits. So the observer, `defaultArm`, the field declarations, and `finish()` are **unchanged**. Only two things differ from a record:

1. **The member source** — for a record, members are the record components; for an `@JSONConstructor` class, members are the **constructor's parameters** (same name/type/order).
2. **The serialize accessor** — a record reads `value.foo()`; a class reads a **resolved** accessor (`getFoo()`/`isFoo()`/`foo()`/public field `foo`), since classes have no bare `foo()` accessor.

Everything else (wire keys, naming, the observer, `finish()`, nested/collection/polymorphic member types) composes through the existing machinery.

## Non-goals

- **No no-arg + setter classes.** A `@JSON` class **without** `@JSONConstructor` is a compile error in this cycle (Cycle 2 adds the JavaBean path).
- **No inherited members beyond accessor lookup.** Members come from the constructor's parameter list only.
- **`@JSONField`/`@JSONCatchAll` on a class *field*** (rather than the constructor parameter) is not consulted — the member is the parameter, so annotate the parameter. (Not an error; just inert on the field, since a record-style "the annotation is on the member" model applies and the member is the param.)
- No runtime change, no public API change beyond the two `@Target` additions.

## Design

### 1. Recognition

The `process()` top-level guard gains a third accepted kind: a `CLASS`. So `@JSON` is valid on a `RECORD`, a sealed `@JSONTypeInfo` `INTERFACE`, or a `CLASS`. A `@JSON` class is routed to the record-style `generateCompanion` path (it produces a `JSONObserver` companion exactly like a record).

A `@JSON` class must have exactly one `@JSONConstructor` constructor (Cycle 1). The generated companion's `fromJSON`/`finish` and `toJSON` use it.

### 2. Members from the `@JSONConstructor` parameters

`generateCompanion`, for a class, builds its `Component` list from the `@JSONConstructor`'s `VariableElement` parameters instead of `getRecordComponents()`. Because a `VariableElement` exposes `getSimpleName()`/`asType()`/`getAnnotation(...)` just like a `RecordComponentElement`, the **same** `Component` logic reads each parameter's `name`, `type`, `wireKey` (`@JSON(naming)` / `@JSONField(name)`), the `@JSONField` policy facts, and `@JSONCatchAll` directly off the parameter — only the serialize accessor (§3) is supplied separately. Parameter order is the constructor argument order, so `finish()` → `new <Class>(this.<p1>, this.<p2>, …)` is correct (unchanged template).

So `@JSONField` (`name`/`ignore`/`readOnly`/`writeOnly`/`format`/`instant`) and `@JSONCatchAll` compose for `@JSONConstructor` classes through the existing Cycle-B / catch-all machinery, with **no** new template work — a parameter annotated `@JSONCatchAll Map<String, Object>` becomes the catch-all member exactly as a record component does. The observer field declarations, case-label accumulation, and `finish()` are the existing record templates, unchanged.

### 3. Serialize accessor resolution

`Component` gains `read()` — the serialize read-accessor **suffix**, such that the builder emits `value.${c.read()}` (today's record code is `value.${c.name()}()`, i.e. `read()` returns `name + "()"` for records). For a class member named `foo` of type `T`, the processor resolves, in order, against the class's **public** members (declared or inherited):

1. `getFoo()` — a public no-arg method;
2. `isFoo()` — a public no-arg method, **only** when `T` is `boolean`/`Boolean`;
3. `foo()` — a public no-arg method (record-style);
4. public field `foo`.

`read()` is then `"getFoo()"`, `"isFoo()"`, `"foo()"`, or `"foo"` respectively. Resolution only matters for a **serialized** member (`serialize()` = not `ignore`, not `writeOnly`): a `writeOnly`/`ignore` parameter is never read out, so it needs no reader (e.g. a `password` consumed by the constructor but never echoed). If a serialized member resolves to none, it's a compile error:

> `no usable reader for member [foo] on [Point]; add a getFoo()/isFoo()/foo()/public field, or mark the parameter @JSONField(writeOnly = true)`

Only public accessors are considered, because the companion lives in `<typePackage>.internal` and must read across the package boundary (the same reason the `@JSONConstructor` constructor must be public).

### 4. Component changes

`Component` exposes `read()`. The record-built `Component` sets `read = name + "()"`; the class-built `Component` sets `read` to the resolved accessor. `companion.jte`'s `builder(...)` replaces every member **value read** `value.${c.name()}()` with `value.${c.read()}` (the `memberCall` `val`, the `List`/`Set`/`Map` `…ToJSON(value.…())` arguments, the `format`/`instant` value reads). The generated `<name>ToJSON` helper names and the observer/`finish()` continue to use `name()` (Java identifiers, not reads).

### 5. Validation (compile-time errors)

- A `@JSON` class with **no** `@JSONConstructor` constructor → error (Cycle 2 relaxes this to allow no-arg + setters).
- More than one `@JSONConstructor` on a class → error.
- `@JSONConstructor` on a **record** → error (redundant; records have a canonical constructor).
- A **serialized** constructor parameter (not `writeOnly`/`ignore`) with **no usable public reader** (§3) → error, with the guidance to add an accessor or mark it `@JSONField(writeOnly = true)`. A `writeOnly`/`ignore` parameter needs no reader.
- An unsupported parameter type (same rule as record components: primitives/boxed/String/BigInteger/BigDecimal/enum/UUID/`java.time`, single-level collections, nested `@JSON`, polymorphic `@JSON`).
- The existing `@JSONField` and `@JSONCatchAll` validation (e.g. `readOnly`+`writeOnly`, `format` on a non-time type, a catch-all that isn't `Map<String, Object>`, more than one catch-all) applies to parameter members unchanged.

### 6. Files touched

- `src/main/java/org/lattejava/json/JSONField.java`, `JSONCatchAll.java` — add `ElementType.PARAMETER` to `@Target`. `JSONConstructor.java` — drop the "TODO" Javadoc line.
- `src/main/java/org/lattejava/json/jte/Component.java` — add `read()`; a constructor (or factory) for a class parameter (a `VariableElement` + the resolved accessor); the existing `@JSONField`/`@JSONCatchAll`/`wireKey` reads work unchanged off the parameter element.
- `src/main/jte/companion.jte` — `builder(...)` value reads use `c.read()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — admit `CLASS`; for a class, find the `@JSONConstructor`, build members from its parameters, resolve each accessor, the §5 validation; the `@JSONConstructor`-on-record and missing/duplicate-`@JSONConstructor` checks.
- `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java` — (optional) assert the new `PARAMETER` target.

### 7. Conventions

New code follows the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/classes/` driven through the real-`javac` `ProcessorHarness`:

- **Round-trip:** a `@JSONConstructor` class with scalar fields and `getFoo()` accessors round-trips byte-exact.
- **Accessor resolution:** a class mixing `getFoo()`, `isActive()` (boolean), bare `foo()`, and a public field — each member reads via the right accessor.
- **Member types compose:** an `@JSONConstructor` class with a `List<...>`, a `Map<...>`, a nested `@JSON` field (record or class), and a `java.time`/enum field round-trips (proving the existing type machinery works off the parameter members).
- **Naming:** `@JSON(naming = SNAKE_CASE)` on a class renames the parameter-derived keys.
- **Parameter config:** `@JSONField(name=…)` and a `@JSONField(readOnly=true)`/`format`/`instant` on constructor parameters behave as on record components; a `@JSONCatchAll Map<String,Object>` parameter captures unknowns and spreads on serialize.
- **Write-only, reader-less:** a `@JSONField(writeOnly=true)` parameter with **no** getter compiles and round-trips (deserialized into the constructor, absent from `toJSON`).
- **Composition:** an `@JSONConstructor` class used as a nested field of a record (and vice-versa) round-trips.
- **Rejections:** class without `@JSONConstructor`; two `@JSONConstructor`s; `@JSONConstructor` on a record; a **serialized** parameter with no public reader (and the message names the `writeOnly` escape hatch); an unsupported parameter type; the existing `@JSONField`/`@JSONCatchAll` rejections on a parameter (e.g. `readOnly`+`writeOnly`, a non-`Map<String,Object>` catch-all).

All existing 242 tests stay green — the record path is untouched (`read()` returns `name()` for record-built members, so generated record companions are byte-identical).

## Risks

- **Accessor/constructor accessibility.** Only public accessors and a public `@JSONConstructor` are usable across the `<typePackage>.internal` boundary; resolving only public members makes "no usable reader" a clean diagnostic rather than a downstream javac error. A non-public `@JSONConstructor` would still produce a javac error on the generated `new <Class>(...)`; a validation check that the constructor is public is a cheap addition.
- **Accessor return type vs parameter type.** The resolved `getFoo()` is assumed to return the parameter's type (serialized via that type). A getter with a divergent return type is an unusual mismatch; not validated in v1 (the generated read would fail to compile if truly incompatible).
- **`read()` generalization must not change records.** `read()` returns `name + "()"` for record-built members, so record companions are byte-identical. Guarded by the still-green record suite.

## Alternatives considered

- **Members from class fields (not constructor parameters).** Rejected for the `@JSONConstructor` path: the constructor parameters are the authoritative deserialize shape and order, and they map 1:1 to keys per the design. Field-driven membership belongs to the Cycle 2 JavaBean path.
- **Deferring `@JSONField`/`@JSONCatchAll` for classes** (or correlating a parameter to its same-named field). Rejected in favor of adding `ElementType.PARAMETER` so the annotations live on the parameter — the member element itself — which makes the existing `Component` reads work unchanged and keeps classes first-class with records, for a one-line `@Target` addition.
- **One cycle for both construction strategies.** Rejected during brainstorming in favor of `@JSONConstructor` first (it reuses the record `finish()`-via-constructor machinery almost entirely), then the JavaBean path.
