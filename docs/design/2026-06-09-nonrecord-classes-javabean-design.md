# Non-record classes: the no-arg + property (JavaBean) path

**Date:** 2026-06-09
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for `@JSON` **classes without** `@JSONConstructor` — the JavaBean pattern: a public no-arg constructor, members are the class's **properties** (defined by their getters/setters, plus public fields), deserialize via setters / public fields, serialize via the resolved accessors from Cycle 1. Members are property-centric — a backing field is **not** required, and how a getter/setter is implemented is irrelevant. This is **Cycle 2** (the final cycle) of non-record-class support. Adds `ElementType.METHOD` to `@JSONField`/`@JSONCatchAll`; no runtime change, no `module-info` change.

## Problem

After Cycle 1, a `@JSON` class **without** a `@JSONConstructor` is a compile error. The mutable-bean alternative — a public no-arg constructor whose properties are populated per parsed key — has no codegen. Crucially, a bean's serializable surface is its **properties** (getter/setter accessors), not its fields: a computed getter, a getter that delegates, or a property with no backing field at all are all legitimate. The model must be property-centric.

## Goal

A `@JSON` class with a public no-arg constructor round-trips its properties:

```java
@JSON
public class Account {
  private String id;
  private int balance;
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public int getBalance() { return balance; }
  public void setBalance(int balance) { this.balance = balance; }
  public int getFeeBps() { return balance > 100 ? 0 : 25; }  // computed, no field, no setter -> read-only property
}
// {"id":"a","balance":5,"feeBps":25}  ⇄  new Account(); setId("a"); setBalance(5);  (feeBps not written back)
```

The existing 254-test suite plus new fixtures are the acceptance gate. **Records and `@JSONConstructor` classes are unaffected.**

## Key insight: a bean ≈ a record on the observer; only `finish()` differs

As in Cycle 1, the observer **still accumulates one `this.<name>` per member** — the entire observer body, `defaultArm`, field declarations, and serialize `builder(...)` are **unchanged**. Two things differ from a record:

1. **The member source** — the class's **properties** (not record components or constructor parameters).
2. **`finish()`** — instead of `new T(this.a, this.b)`, it emits `T value = new T(); value.setA(this.a); value.b = this.b; return value;` (a setter call or public-field assignment per *deserialized* member).

Serialize reuses the Cycle 1 read-accessor mechanism (`Component.read()`); for a bean the resolution is `getFoo()`/`isFoo()`/public field — without the bare `foo()` form (§2). Cycle 2 adds the symmetric **write** accessor (`setFoo(...)` / public field) for `finish()`.

**Read-only / write-only fall out for free.** A property with a getter but no setter is read-only; with a setter but no getter, write-only. Rather than thread "has reader / has writer" through the templates, the processor **folds accessor presence into the existing `readOnly`/`writeOnly` facts** at `Component`-build time: `effectiveReadOnly = @JSONField.readOnly || noWriter`, `effectiveWriteOnly = @JSONField.writeOnly || noReader`. Then `serialize()` / `deserialize()` and every template keep working untouched — a read-only property is simply not deserialized, a write-only one not serialized.

## Non-goals

- **No bare accessors.** Getters are `getFoo()`/`isFoo()`; setters are `setFoo(T)`. A bare `foo()` / `foo(value)` (no prefix) is not an accessor here — unlike a `@JSONConstructor` parameter's accessor in Cycle 1, a bean has no independent signal to distinguish a bare accessor from an ordinary method. Prefixed accessors only.
- **No fluent setters.** Only a `void setFoo(T)` (one parameter) is a writer; a `T setFoo(T)` returning `this` is not (a future, compatible extension).
- **No `@JSONConstructor` interaction.** A class *with* `@JSONConstructor` keeps the Cycle 1 path; this cycle is strictly the no-`@JSONConstructor` case.
- No runtime change, no `module-info` change. The only public-API change is `ElementType.METHOD` on the two annotations.

## Design

### 1. Recognition

A `@JSON` class with **no** `@JSONConstructor` (today an error) is routed to the bean path. It must have a **public no-arg constructor** (else a compile error). A class *with* `@JSONConstructor` is unchanged (Cycle 1).

### 2. Member discovery — properties, inherited included

Members are the bean's **properties**, deduplicated by property name, collected from (over the leaf type and its superclasses, excluding `Object`):

- a public, non-static getter `getFoo()` or `isFoo()` (boolean-returning), no parameters → property `foo`;
- a public, non-static setter `setFoo(T)` (one parameter) → property `foo`;
- a public, non-static field `foo` → property `foo`;
- a non-static field `foo` carrying `@JSONField` or `@JSONCatchAll` (explicit opt-in, even if private) → property `foo`.

Only the **prefixed** `getFoo`/`isFoo`/`setFoo` accessor forms define properties — a bare `foo()` method is **not** a getter here. Unlike a `@JSONConstructor` parameter's accessor in Cycle 1, where the member is already known and `foo()` is unambiguously its accessor, a bean has no independent signal to tell a bare accessor from an ordinary method without an annotation. `getClass()` (a `getX` form on `Object`) is the one prefixed false-positive, so `java.lang.Object`'s methods are excluded from discovery; a `getX`/`isX` method you don't want serialized can be excluded with `@JSONField(ignore)` (now allowed on a method — §3).

Accessors are found via `getAllMembers` (inherited public getters/setters/fields included). Fields — including inherited **private** ones, which `getAllMembers` hides — are found by walking `getSuperclass()` + `getEnclosedElements()`. Wire order is **base-class properties first**, then the leaf class's. `static` members are excluded; a property whose backing field is `transient` is **skipped** (Java's "don't persist" marker).

### 3. Per-property resolution

For each property `foo`:

- **read** (serialize) — `getFoo()` → `isFoo()` (boolean) → public field `foo` (the Cycle 1 order **minus** the bare `foo()` form, which is not a bean accessor — §2). None ⇒ no reader.
- **write** (deserialize) — new: public `setFoo(T)` (one param) → public field `foo`. None ⇒ no writer. `Component` gains `write()` (the Java name) and `writeIsSetter()`; `finish()` emits `value.setFoo(this.foo);` or `value.foo = this.foo;`.
- **type** — from the reader's return type, else the writer's parameter type, else the field type.
- **config element** (for `@JSONField`/`@JSONCatchAll`/`@JSONField(name)`) — **the backing field first** (a same-named field, declared or inherited), then the getter, then the setter; the first bearing the annotation wins. This requires `ElementType.METHOD` on both annotations so they may sit on an accessor.
- **wire key** — `@JSONField(name)` from the config element, else `@JSON(naming)` applied to the **property name**.
- **effective direction** — `readOnly = @JSONField.readOnly || (write == none)`, `writeOnly = @JSONField.writeOnly || (read == none)`; `ignore`/`format`/`instant` from the config element. A property with **neither** reader nor writer (e.g. an annotated private field with no accessor) is a compile error (§5).

`Component` gains a constructor taking these resolved facts explicitly (the per-property name/type/wire-key/policy/read/write are no longer all derivable from one `Element`).

### 4. `finish()` — the bean variant

`CompanionView` gains `beanConstructed()` (true only for a no-`@JSONConstructor` class). `observerBody.jte`'s `finish()` branches:

- record / `@JSONConstructor` class (existing): `return new <T>(this.a, this.b);`
- bean: build, write each *deserialized* member, return:

```java
@Override public <T> finish() {
  <T> value = new <T>();
  value.setA(this.a);   // @if writeIsSetter
  value.b = this.b;     // @else (public field)
  return value;
}
```

Only `deserialize()` members are written (read-only properties are skipped; their shared `private` accumulation field is harmlessly unused). The observer body, `defaultArm`, declarations, and serialize `builder(...)` are untouched. A `@JSONCatchAll` property composes: the observer accumulates it (default arms → `this.<catchAll>.put(...)`), `finish()` writes it via its setter/field, serialize spreads via its reader.

### 5. Validation (compile-time errors)

For a `@JSON` class with no `@JSONConstructor`:

- No **public no-arg constructor** → error ("requires a public no-arg constructor, or a @JSONConstructor").
- A property with **neither a usable reader nor a usable writer** (e.g. an `@JSONField`-annotated private field with no accessor) → error naming the property.
- Zero discovered properties → error (nothing to serialize; likely a mistake).
- An unsupported property type, and all existing `@JSONField`/`@JSONCatchAll` rejections, apply via `validateMembers` over the resolved members (unchanged).

Note: a property missing only one direction is **not** an error — it is read-only or write-only (the normal bean case), enforced via the folded `readOnly`/`writeOnly` facts (§3), not a diagnostic.

### 6. Files touched

- `src/main/java/org/lattejava/json/JSONField.java`, `JSONCatchAll.java` — add `ElementType.METHOD` to `@Target`.
- `src/main/java/org/lattejava/json/jte/Component.java` — add `write()` + `writeIsSetter()`; a property constructor taking explicit resolved facts.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — add `beanConstructed()`.
- `src/main/jte/observerBody.jte` — `finish()` branches on `beanConstructed()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — route a no-`@JSONConstructor` class to the bean path: property discovery (`getAllMembers` accessors + superclass-walk fields), `resolveRead`/`resolveWrite`, config-element resolution (field→accessor), effective-direction folding, the bean branch in `generateCompanion` + `validateClass`, and `beanConstructed = true`.

### 7. Conventions

New code follows the project rules: SPDX header, uppercase acronyms, `[brackets]` around runtime values in error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/beans/` driven through the real-`javac` `ProcessorHarness`:

- **Round-trip:** a bean with private fields + `getFoo`/`setFoo` round-trips byte-exact (`finish()` uses setters).
- **Computed / read-only property:** a getter with no setter and no field (e.g. `getFeeBps()`) serializes but is not written back (read-only); a setter with no getter is write-only.
- **Public-field property:** a `public` field with no accessors reads/writes via `value.foo`.
- **Inheritance:** a bean extending a base with its own (private, accessor-backed) properties — base properties are members, base-first in wire order, via inherited accessors.
- **Member types compose:** a bean with a `List`/`Map`, a nested `@JSON` record/class, a `java.time`/enum property round-trips.
- **`transient`/`static` skipped:** a `transient` field's property and a `static` field are omitted.
- **Config on field vs accessor:** `@JSONField(name=…)` on a **field** wins; the same on a **getter/setter** (no field, or field unannotated) is honored; `@JSONCatchAll` on a property; `format`/`instant`/`readOnly`/`writeOnly` on a property behave as on record components.
- **Rejections:** no public no-arg constructor; a property with neither reader nor writer (annotated private field, no accessor); zero properties; an unsupported property type.

All existing 254 tests stay green — records and `@JSONConstructor` classes take their existing paths (`beanConstructed()` is false; `finish()` keeps the constructor form).

## Risks

- **`getAllMembers` hides inherited private fields.** Inherited fields (for public-field discovery, annotated-field discovery, `transient` detection, and the field-first config lookup) are found by walking `getSuperclass()` + `getEnclosedElements()`; their *accessors* are resolved via `getAllMembers` on the leaf. The two-source split is the load-bearing subtlety.
- **Config-element ambiguity.** `@JSONField` could appear on both the field and an accessor; the field-first, then getter, then setter order is the defined precedence (first bearing the annotation wins). Documented; not an error.
- **Property identity / capitalization.** `getFoo`→`foo`, `getURL`→`url` (decapitalize the first letter after the prefix). Edge cases (`getX`, single-letter) follow the standard JavaBeans `Introspector.decapitalize` rule; codify it so discovery and wire keys agree.
- **`finish()` is the only template divergence.** A regression surfaces as a non-record companion failing to construct; the record/`@JSONConstructor` suites guard the constructor form (`beanConstructed()` false).

## Alternatives considered

- **Field-centric members (a backing field is required; fieldless getter/setter is not a member).** Rejected — a bean's surface is its properties; computed/delegating/fieldless properties are legitimate and must be members.
- **Config on the accessor only (METHOD target), ignoring fields.** Rejected in favor of field-first-then-accessor, so existing field-annotated beans keep working and the annotation can live wherever is natural.
- **Eager construction in the observer** (`private T value = new T();` then `case … -> value.setFoo(...)`). Rejected: it diverges the entire observer body; accumulate-then-`finish()` reuses it wholesale.
- **A hard "missing accessor" error for one-directional properties.** Rejected — read-only/write-only properties are normal; only a zero-accessor member is an error.
