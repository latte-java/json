# Nested collections via type plans (`Map<String, List<Domain>>` and arbitrary nesting)

**Date:** 2026-06-12
**Status:** Implemented
**Scope:** Support arbitrarily nested collection member types — `Map<String, List<Domain>>` (the motivating case), `Map<K, Set<E>>`, `List<List<E>>`, `Map<K, Map<K2, V>>`, and any deeper combination — by replacing the per-member flat collection codegen with **type plans**: a static, per-member description of the collection type tree, interpreted by two new reusable runtime observers on the read side and a recursive plan writer on the write side. `@JSON` object leaves still serialize/deserialize through their generated companions; scalar leaves keep their exact narrowing/conversion semantics via generated lambdas. **All collection members (including today's one-level `List`/`Set`/`Map`) move onto plans** — the typed `<Name>MapObserver`/`<Name>ArrayObserver` inner-class codegen is deleted. Dynamic maps (`Map<String, Object>`) and `@JSONCatchAll` keep their existing dedicated paths.

## Problem

Collection codegen today is flat and per-member: each `List`/`Set`/`Map` member generates one specialized observer inner class and one `<name>ToJSON` serializer, and every nested-collection position **throws or is rejected**:

- `AbstractValidator.validateType` rejects a collection-typed Map value: `"uses a nested collection as a Map value [...] which is not supported in this release"` — so `Map<String, List<Domain>>` does not compile.
- It likewise rejects a collection-typed List/Set element (`List<List<String>>`).
- The generated observers hard-throw: `mapObserver.jte` `beginArray` → `"nested collections unsupported"`; `arrayObserver.jte` `beginArray` → same.

The parser infrastructure does **not** have this limitation — `JSONObserver`/`JSONArrayObserver` are push-driven and a parent observer returns a child observer for `beginObject`/`beginArray` (this is exactly how `AnyObjectObserver`/`AnyArrayObserver` recurse for dynamic values). The limitation is purely in validation and in the flat, codegen-only observer design, where supporting depth would require generating an observer class per type-node, with structural naming and cross-member deduplication.

## Goal

Any `@JSON` member whose type is a finite composition of `List`/`Set`/`Map` over supported leaf types round-trips:

```java
@JSON
public record Catalog(Map<String, List<Product>> byCategory) {}
// {"byCategory":{"tools":[{"sku":"a"},{"sku":"b"}],"toys":[]}}
//   ⇄  byCategory = {tools=[Product[sku=a], Product[sku=b]], toys=[]}
```

- **Leaves:** anything a one-level collection supports today — nested `@JSON` records/classes, polymorphic `@JSON` sealed interfaces, enums, `String`, `UUID`, `java.time` types, numerics, booleans.
- **Containers:** `List`, `Set` (`LinkedHashSet`, order-preserving), `Map` (string-form keys — `String`, `UUID`, enum, `java.time` — at **every** map level; `LinkedHashMap`, order-preserving).
- **Depth:** unbounded (a Java type is finite by construction; no artificial cap).
- **Behavior parity:** existing one-level members keep byte-exact round-trips after migrating onto plans; the full existing test suite is the regression gate.

## Non-goals

- **No `Object` leaves inside collections.** `Map<String, List<Object>>`, `List<Object>`, and a *nested* `Map<String, Object>` (e.g. `Map<String, Map<String, Object>>`) stay compile errors. The dynamic-map feature (2026-06-10) is deliberately scoped to a **direct member** of type `Map<String, Object>`; folding "any" values into plans is a possible future unification, not this cycle.
- **Dynamic maps and `@JSONCatchAll` keep their existing codegen.** A direct `Map<String, Object>` member still routes to `AnyObjectObserver` + the `any`-based serializer; the catch-all path is untouched.
- **No raw/wildcard types at any level.** A raw `List`/`Map` or `List<?>` anywhere in the tree stays rejected (missing type argument ⇒ error, as today).
- **No `@JSONField(format)`/`instant` reach-through.** Per-member temporal formatting still applies only to scalar temporal members, not to temporals buried inside collections (unchanged from today).
- No public API change. New runtime types live only in the emitted per-module `internal` package (and the canonical `org.lattejava.json` sources), like every other helper.

## Design

### 1. The core idea: plans instead of per-node classes

A member type like `Map<String, List<Product>>` has a **static structure** known at codegen time. Instead of generating one observer *class* per type node (which needs structural naming and dedup), the companion emits one static **plan** per collection member — a tree of small descriptor objects — and two reusable runtime observers interpret it:

```java
// generated into CatalogJSON:
private static final JSONPlan.Node<Map<String, List<demo.Product>>> byCategoryPlan =
    JSONPlan.map(k -> k, k -> k,
        JSONPlan.list(
            JSONPlan.object(demo.internal.ProductJSON::new, demo.internal.ProductJSON::toJSON)));
```

All *type knowledge* (key conversion, scalar narrowing, leaf companions) is still generated code — it lives in the typed lambdas/method references inside the plan. The node model is **generic in the Java type it produces** (`Node<T>`), so plan construction is compile-checked end to end: leaf writers are plain method references (no erasure casts in generated code), and `JSONPlan.write(plan, value, ...)` type-checks the member value against its plan — a structurally wrong plan fails compilation rather than surfacing as a `ClassCastException` at parse time. The *recursion* moves into runtime classes written once, exactly as `AnyObjectObserver`/`AnyArrayObserver` already recurse for dynamic values. This eliminates the hard parts of a fully-codegen'd recursive design: no structural class names, no dedup registry, no recursive observer-class templates.

### 2. New runtime helpers (3 files, `JSON*`-prefixed)

All three are normal helper sources in `org.lattejava.json`, added to `HelperEmitter.HELPERS` and picked up automatically by `project.latte`'s existing helper-copy glob (`/JSON.*/`) — **no build change**.

**`JSONPlan`** — the node model, factories, and the serialize walker, in one file:

- `Node<T>` — sealed interface over the node kinds, **generic in the Java type `T` the node produces/consumes** (nested types inside `JSONPlan`):
  - `ListNode<E>(Node<E> child)` implements `Node<List<E>>` / `SetNode<E>(Node<E> child)` implements `Node<Set<E>>`
  - `MapNode<K, V>(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child)` implements `Node<Map<K, V>>`
  - `ObjectLeaf<T>(Supplier<JSONObjectHandler> observer, Function<T, String> writer)` — `@JSON` record/class companions **and** polymorphic dispatchers (both have `new <X>JSON()` observers and static `toJSON`); the writer is a plain method reference (`ProductJSON::toJSON`)
  - `ScalarLeaf<T>(...)` — typed read converters per parser callback (`Function<String, T> fromString`, `LongFunction<T> fromInteger`, `Function<BigInteger, T> fromBigInteger`, `Function<BigDecimal, T> fromDecimal`, `boolean acceptsBool`; a null converter means that callback is illegal for this leaf and throws) plus two typed write lambdas (`BiConsumer<JSONArrayBuilder, T> append` for array positions, a keyed `write(JSONBuilder, String, T)` for map positions). The generated lambdas reuse exactly the expressions `fromString.jte`/`narrow.jte`/`arrayAppend.jte`/`memberCall.jte` produce today — cast-free because the leaf is typed (e.g. `(b, e) -> b.integer(e == null ? null : e.longValue())` for an `Integer` leaf).
- Static generic factories composing the types: `<E> Node<List<E>> list(Node<E> child)`, `<E> Node<Set<E>> set(Node<E> child)`, `<K, V> Node<Map<K, V>> map(keyReader, keyWriter, Node<V> child)`, `<T> Node<T> object(observer, writer)`, plus scalar-leaf factories. Generated code may use explicit type witnesses (`JSONPlan.<demo.Product>object(...)`) where inference needs help — still cast-free.
- **Serialize walker:** `<T> String write(Node<T> node, T value, boolean omitNulls)` — recursively builds the raw JSON: a `ListNode`/`SetNode` drives a `JSONArrayBuilder` (leaf child → `append` lambda; container child → recurse and `.raw(...)`; `ObjectLeaf` child → `.raw(writer.apply(e))`); a `MapNode` drives a `new JSONBuilder(omitNulls)` (leaf child → keyed `write` lambda; `ObjectLeaf` child → `.object(key, writer.apply(v))`; container child → recurse into `.array(key, ...)`/`.object(key, ...)`). Null elements/values follow today's conventions: array elements always written (`null` stays `null`), map entries respect `omitNulls`.

**`JSONPlanMapObserver<K, V> implements JSONObserver<Map<K, V>>`** — holds a `MapNode<K, V>`; accumulates into a `LinkedHashMap<K, V>`. Scalar callbacks apply the child `ScalarLeaf` converters (or throw `JSONProcessingException("unexpected JSON value ...")` when the child isn't a scalar leaf / the callback is illegal); `beginObject(key)` returns the child `ObjectLeaf`'s observer or a `new JSONPlanMapObserver<>(childMapNode)`; `beginArray(key)` returns a `JSONPlanArrayObserver` over the child list/set node; `object`/`array` callbacks `put(keyReader.apply(key), value)`; `nullValue` puts `null`. `finish()` returns the typed map (the generated companion's existing `object(key, value)` arm casts to the declared type, as today).

**`JSONPlanArrayObserver<E, C extends Collection<E>> implements JSONArrayObserver<C>`** — holds a `ListNode<E>` or `SetNode<E>`; accumulates into `ArrayList` or `LinkedHashSet` per the node kind. Positional mirror of the map observer; `finish()` returns the typed collection.

**Where casts remain (and only there):** the parser boundary is untyped — `object(String, Object)`/`array(Object)` callbacks deliver `Object`, and the generic dispatch inside `JSONPlan.write` cannot prove `Node<T>` + `ListNode<E>` ⇒ `T = List<E>` without a helper-method cast. These unchecked casts are confined to a few `@SuppressWarnings("unchecked")` sites inside the three write-once runtime helpers (plus the pre-existing declared-type cast in the generated `object`/`array` assignment arms). Generated plans contain **no casts**.

`JSONObjectHandler` is sealed permitting `JSONObserver`/`JSONPolymorphicObserver` — `JSONPlanMapObserver` implements `JSONObserver`, so it fits the parser contract unchanged.

### 3. Codegen — what the templates emit now

**`mapObserver.jte` + `arrayObserver.jte` are replaced** by plan emission (one new template, e.g. `plan.jte`, with a recursive node partial `planNode.jte`):

- Per collection member: `private static final JSONPlan.Node <name>Plan = <recursive expression>;`
- The recursive expression is assembled by `planNode.jte` calling itself for child nodes (JTE templates compile to methods, so self-recursion is expected to work — **verify in the first implementation task**; the fallback is assembling the expression string in a recursive Java helper on `Component`/`TypeView`'s template-facing layer).
- The typed `<Name>MapObserver`/`<Name>ArrayObserver` inner classes and per-member `<name>ToJSON` methods are **no longer generated** (companions shrink). The dynamic-map branch of `mapObserver.jte` (the `any`-based `<name>ToJSON`) survives, relocated but unchanged in behavior.

**`companion.jte`** serialize call sites change from `<name>ToJSON(value.<read>)` to the plan writer:

- `List`/`Set` member: `.array("<wireKey>", value.<read> == null ? null : JSONPlan.write(<name>Plan, value.<read>, <omitNulls>))`
- `Map` member: `.object("<wireKey>", value.<read> == null ? null : JSONPlan.write(<name>Plan, value.<read>, <omitNulls>))`
- Imports: `JSONPlan`, `JSONPlanArrayObserver`, `JSONPlanMapObserver` when any (non-dynamic) collection member exists.

**`observerBody.jte`** routing:

- `beginArray(key)` for a `List`/`Set` member: `return new JSONPlanArrayObserver(<name>Plan);`
- `beginObject(key)` for a typed `Map` member: `return new JSONPlanMapObserver(<name>Plan);` (dynamic maps keep `new AnyObjectObserver()`)
- The `object(key, value)` / `array(key, value)` assignment arms keep their existing casts to the declared type.

### 4. Validation — recursive walk

`AbstractValidator.validateType`'s flat collection checks become a recursive walk over the type tree (member-level entry point unchanged):

```
validate(node):
  Map  → key non-null and string-form (error: existing "unsupported Map key type" message, at every level)
         value null (raw/wildcard) → error
         value is collection → recurse
         value is String-keyed Object → error UNLESS this Map is the direct member type (dynamic map)
         else → isSupportedComponentType(value)
  List/Set → element null (raw/wildcard) → error
             element is collection → recurse
             else → isSupportedComponentType(element)
```

Lifted rejections (now valid): "nested collection as a Map value", "nested collection [element]" for `List<List<...>>` etc. Preserved rejections: raw/wildcard at any level, non-string-form map keys at any level, unsupported leaf types (including `Object` anywhere except the direct dynamic-map member), un-annotated records/classes (the `notJSON` diagnostic, now also produced for deep leaves). Error messages name the member and the offending type, `[bracketed]` per convention.

### 5. Interaction with existing features

- **Dynamic maps / catch-all:** untouched (Non-goals). `isDynamicMap()` continues to gate the direct-member special case before the typed-map path.
- **Polymorphic leaves:** an `ObjectLeaf` whose supplier is the polymorphic dispatcher (`PetJSON::new`) and whose writer is the dispatcher's `toJSON`. `TypeView.hasCompanion()` already unifies nested + polymorphic — plans reuse it.
- **`omitNulls`:** threaded into `JSONPlan.write` per companion, applied at every map level (matching today's per-map-serializer behavior); array elements always written.
- **Thread-safety:** plans are immutable static finals; observers are per-parse instances. Same model as today.
- **Generated-code size/perf:** companions shrink (no observer inner classes); parsing gains one descriptor indirection per event — negligible against parse cost, and the common scalar path is a single lambda invocation.

### 6. Files touched

**New runtime (canonical sources in `src/main/java/org/lattejava/json/`):** `JSONPlan.java`, `JSONPlanMapObserver.java`, `JSONPlanArrayObserver.java` (+ unit tests). Add the three names to `HelperEmitter.HELPERS`. No `project.latte` change (the `/JSON.*/` glob already copies them).

**Templates:** replace `mapObserver.jte`/`arrayObserver.jte` with `plan.jte` + recursive `planNode.jte` (+ scalar-leaf lambda partial reusing `fromString`/`narrow`/`arrayAppend`/`memberCall` logic); update `companion.jte` (imports + serialize call sites + template includes); update `observerBody.jte` (collection routing). `arrayAppend.jte`/`memberCall.jte` remain for scalar member serialization and are also reused (or adapted) for leaf lambda bodies.

**Processor:** `AbstractValidator.validateType` → recursive walk. `TypeView` likely needs no change (`element()`/`key()`/`value()` already navigate type arguments recursively); `CompanionView.collectionComponents()` unchanged.

**Tests:** see below; `badcollections` fixtures and `CollectionRejectionTest` are restructured because `List<List<String>>` becomes legal.

### 7. Conventions

New code follows the project rules: SPDX headers, uppercase acronyms (`JSONPlan...`), `[brackets]` around runtime values in error messages, module imports, alphabetization, in-class member order.

## Testing — acceptance gate

**Behavior parity (the big one):** the entire existing suite (282) stays green with every existing collection fixture rerouted through plans — `Maps`, `KeyedMaps`, `Lists`, `Sets`, `EnumColls`, `User` (List/Set/Map of nested objects), polymorphic collections, dynamic maps, catch-all. Existing byte-exact round-trip assertions pin serialization parity.

**New positive fixtures** (new `deepcollections` fixture set + codegen test):

- `Map<String, List<Domain>>` — the motivating case: round-trip, empty list values, null elements inside lists, insertion order at both levels.
- `Map<Enum, Set<Domain>>` — string-form key + Set value (LinkedHashSet order preserved).
- `List<List<String>>` — nested list of scalars (moved from the rejection fixtures to positive).
- `Map<String, Map<String, Domain>>` — map-in-map with object leaves.
- `Map<String, List<Map<Instant, Integer>>>` — three levels, scalar leaf with narrowing, java.time key.
- Polymorphic leaf: `Map<String, List<Shape>>` where `Shape` is `@JSON @JSONTypeInfo`.
- `omitNulls` true/false at nested map levels; whole-member null.
- Naming strategy applied to the member wire key (inner map keys are data, never renamed — assert that).

**Rejection fixtures** (updated `badcollections` + new):

- Raw/wildcard at depth: `Map<String, List>` (raw), `List<List<?>>`.
- Non-string-form key at depth: `Map<String, Map<Integer, String>>`.
- `Object` leaves: `Map<String, List<Object>>`, `Map<String, Map<String, Object>>` (nested dynamic-map shape).
- Un-annotated record at depth: `Map<String, List<PlainRecord>>` → `notJSON` diagnostic.
- Existing kept rejections re-asserted: raw member collections, bad top-level map keys.

**Runtime helper unit tests:** `JSONPlan.write` shapes (nested containers, nulls, omitNulls threading), `JSONPlanMapObserver`/`JSONPlanArrayObserver` driven through `JSONParser` directly with hand-built plans, mismatch errors (scalar where array expected, etc.).

## Risks

- **Behavior parity across the migration.** Re-routing *all* existing collection members through plans is the deliberate blast radius of the unify decision. Mitigation: the 282-test suite (byte-exact round-trips throughout) runs after every task; parity bugs surface immediately.
- **JTE self-recursion for `planNode.jte`.** Believed to work (templates compile to methods); **verified as the first implementation step**. Fallback: assemble the plan expression in a recursive Java helper.
- **Scalar-leaf lambda fidelity.** The lambdas must reproduce exactly the conversions the typed observers performed (`Numbers.toIntExact`, `Conversions.toEnum`, boxed-vs-primitive null handling in writes). Mitigation: generate them from the same template logic (`fromString`/`narrow`/`arrayAppend`/`memberCall`), and the parity suite pins behavior.
- **Unchecked casts at the parser boundary.** The generic `Node<T>` model makes generated plans cast-free and compile-checked (a structurally wrong plan fails compilation), but the runtime helpers still need a few `@SuppressWarnings("unchecked")` casts where the parser delivers `Object` and where the sealed-generic dispatch in `JSONPlan.write` narrows. These are write-once, unit-tested sites; a defect there would surface as a `ClassCastException` in the round-trip suite, which covers every shape.
- **Sealed-interface exhaustiveness.** `JSONPlan.Node` being sealed keeps the observers' dispatch exhaustive — a future node kind fails compilation in the observers rather than silently misbehaving.

## Alternatives considered

- **Fully-codegen'd recursive observers** (one generated class per type node): all knowledge in generated code, no runtime interpretation — but requires structural class naming, cross-member dedup, and recursive class-emitting templates; the largest and riskiest variant. Rejected in favor of plans, which keep type knowledge in generated lambdas while moving recursion into two write-once runtime classes.
- **One-level-only relaxation** (`Map<K, List/Set<E>>` with non-collection `E`, pure codegen): smallest change covering the literal request, but leaves the architecture flat (the next depth request forces the redesign anyway) and was explicitly declined in favor of the general solution.
- **Plans for nested collections only, flat codegen kept for one-level members:** two coexisting mechanisms; zero regression risk for existing members but permanent duplication of the collection machinery. Declined — unification chosen, with the test suite as the parity gate.
- **Folding dynamic maps (`Map<String, Object>`) into plans via an "any" leaf:** elegant unification (would also enable `List<Object>` etc.) but expands scope beyond this cycle and re-opens the deliberate non-goals of the dynamic-map design. Deferred; the plan model accommodates it later (`AnyLeaf` returning `AnyObjectObserver`/`AnyArrayObserver`).
