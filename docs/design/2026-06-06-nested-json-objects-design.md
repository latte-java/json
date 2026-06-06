# Nested `@JSON` objects

**Date:** 2026-06-06
**Status:** Approved (design); pending implementation plan
**Scope:** Annotation-processor codegen for record components whose type is itself an `@JSON` record — as a direct field, a `List`/`Set` element, or a `Map` value. Pure codegen change: no public API change, no `module-info` change, no new runtime-helper code (the raw-embed builder methods already exist). Builds on the already-shipped nested-dispatch runtime.

## Problem

The runtime already supports object nesting — `JSONObserver.beginObject(String key)` returns a child `JSONObjectHandler`, the parser drives it to `finish()`, and the result is delivered back via `object(String key, Object value)` (mirrored on `JSONArrayObserver`). But the **code generator rejects every nested `@JSON` component at compile time**: `isSupportedComponentType` returns false for a nested record, and the collection/map inner observers emit `throw new JSONProcessingException("nested objects in collections unsupported")`. So the headline capability the design promises — records inside records, and collections/maps of records — is unreachable from `@JSON`.

## Goal

Generate the codegen that drives the existing runtime, so a record component may be a nested `@JSON` record in three positions:

- **Direct field** — `record User(String name, Address address)`
- **Collection element** — `List<Address>`, `Set<Address>`
- **Map value** — `Map<K, Address>` where `K` remains string-form (`String`, `UUID`, an `enum`, a `java.time` type)

…and round-trip each correctly, including deep and recursive nesting. The existing 173-test processor suite plus new nested fixtures are the acceptance gate.

## Non-goals

- **No polymorphism codegen.** Sealed `@JSONTypeInfo` / `@JSONSubtype` is a separate cycle. A nested type here is a concrete `@JSON` record.
- **No transitive auto-generation.** A nested type must be **explicitly** `@JSON`-annotated; the processor does not generate companions for un-annotated reachable records. (Considered and deferred — see "Alternatives".)
- **No cross-module nesting.** A nested `@JSON` type in a different module is a compile-time error, per the existing v1 restriction.
- **No non-record nested types.** Nested classes wait for the non-record-classes cycle.
- No public API change, no `module-info` change, no new runtime-helper code.

## Design

### 1. Rules

A record component participates in nesting when its type — or its `List`/`Set` element type, or its `Map` value type — is an `@JSON`-annotated record. Such a component is accepted iff the nested type is:

1. **`@JSON`-annotated**, and
2. **in the same module** as the type being processed.

Otherwise the processor reports a compile-time error on the offending component and generates no companion for the enclosing type:

- Not `@JSON`: `component [address] of type [demo.User] references [demo.Address], which is not @JSON-annotated`
- Different module: `component [address] of type [demo.User] references @JSON type [other.Address] in module [other.mod]; cross-module @JSON references are not supported`

The same-module guarantee is what makes the generated references legal: within one module, `userpkg.internal.UserJSON` may freely reference `addrpkg.internal.AddressJSON` and `addrpkg.Address` regardless of `exports`.

**Map keys are unchanged.** Only the map *value* may be nested; the key must still be string-form, enforced by the existing key validation.

**Recursion and cycles work by construction.** `record Tree(String name, List<Tree> kids)` generates a single `TreeJSON`; the parent's `beginObject`/`beginArray` returns a fresh `TreeJSON` child at runtime, so arbitrarily deep and self-referential graphs resolve without any special codegen. Parse depth is bounded by the parser's existing `maxNestingDepth`.

### 2. Type classification (`TypeView`)

Add to `TypeView`:

- `isNested()` — the type's `DeclaredType` element is a record carrying `@JSON`.
- The nested companion's fully-qualified name (`<typePackage>.internal.<Simple>JSON`) and the nested type's fully-qualified name, for import emission.

The processor gains two small helpers: `isJSON(TypeMirror)` (element has `@JSON`) and a same-module check via `Elements.getModuleOf`. `isNested()` composes with the existing `isCollection()`/`kind()` so a `List<Address>` is recognized as "collection whose element is nested".

### 3. Validation (`JSONProcessor.validateComponents` / `isSupportedComponentType`)

A component is now supported if it is nested, or a `List`/`Set`/`Map` whose element/value is nested, *in addition to* the existing scalar/string-form/collection cases. When a candidate looks like a nested reference but fails rule (1) or (2), emit the corresponding error above. This is also where the cross-module check — absent today but previously unreachable — comes to live.

### 4. Serialization codegen

Embed the child's `toJSON` output as raw JSON, reusing builder methods that already exist: `JSONBuilder.object(String key, String rawJson)` for object members and `JSONArrayBuilder.raw(String rawJson)` for array elements. The child's static `toJSON(T)` is reused; there is one code path and no new per-field generation logic — and no new runtime helper.

- **Direct field:** `.object("address", v.address() == null ? null : AddressJSON.toJSON(v.address()))`
- **`List`/`Set<Address>`:** the generated `addressesToJSON(...)` helper appends each element raw: `e == null ? b.nullValue() : b.raw(AddressJSON.toJSON(e))`
- **`Map<K, Address>`:** the generated `…ToJSON(...)` helper writes `b.object(<wireKey>, val == null ? null : AddressJSON.toJSON(val))`

`null` nested values follow the existing `omitNulls` policy for object members; in arrays a `null` element is emitted as `null` (array length is significant), consistent with current array behavior.

### 5. Deserialization codegen

Drive the child observer and assign the finished value. Every hook already exists on the runtime interfaces.

- **Direct field** (`observerBody.jte`): add a `beginObject` arm `case "address" -> { return new AddressJSON(); }` and an `object` arm `case "address" -> this.address = (Address) value;`.
- **`List`/`Set<Address>`** (`arrayObserver.jte`): replace the throwing `beginObject()` stub with `return new AddressJSON();` and the empty `object(Object value)` with `acc.add((Address) value);`.
- **`Map<K, Address>`** (`mapObserver.jte`): replace the throwing `beginObject(String key)` with `return new AddressJSON();` and the empty `object(String key, Object value)` with `map.put(<keyFromString>, (Address) value);`.

The casts are safe because codegen knows each position's declared element/value type. For non-nested components these arms are unchanged (scalars still throw "unsupported" on a stray nested object, preserving strictness).

### 6. Imports

The parent companion imports each distinct nested companion (`addrpkg.internal.AddressJSON`) and nested type (`addrpkg.Address`), collected during component iteration the same way enum imports already are and rendered through the existing `{{enumImports}}`-style standalone hole in `companion.jte` (generalized to "additional imports"). Deduplicated and alphabetized per project conventions. Same-module legality is guaranteed by the rules in §1.

### 7. Templates and files touched

- `src/main/jte/companion.jte` — emit nested-companion/type imports; nested serialization member calls.
- `src/main/jte/observerBody.jte` — nested `beginObject`/`object` arms for direct fields.
- `src/main/jte/arrayObserver.jte` — real nested element dispatch (replaces stub).
- `src/main/jte/mapObserver.jte` — real nested value dispatch (replaces stub).
- `src/main/java/org/lattejava/json/jte/TypeView.java` — `isNested()` + nested-name accessors.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` / `Component.java` — surface nested imports/flags to the templates.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — `isJSON`/same-module helpers, validation, nested-import collection.

No runtime-helper file changes: `JSONArrayBuilder.raw(String)` and `JSONBuilder.object(String, String)` already provide the raw-embed support serialization needs.

### 8. Conventions

New code follows the project rules: SPDX header on any new Java file, uppercase acronyms, `[brackets]` around runtime values in compile-time and runtime error messages, module imports, alphabetization and in-class member order.

## Testing — acceptance gate

New fixtures under `src/test/resources/fixtures/nested/` (and a cross-module fixture) plus codegen tests driven through the real-`javac` `ProcessorHarness` (compile with the processor attached, class-load, JSON round-trip):

- Direct nested field round-trip (`User` → `Address`).
- `List<Address>` and `Set<Address>` round-trip (Set preserves insertion order, dedups).
- `Map<enum, Address>` round-trip (string-form key + nested value).
- Deep nesting: `User → Address → Geo`.
- Recursion: `Tree(String, List<Tree>)`.
- `omitNulls` behavior for a null nested field (omitted by default; emitted as `null` under `@JSON(omitNulls=false)`).
- Rejection: nested type missing `@JSON` → compile error naming the component.
- Rejection: nested `@JSON` type in a different module → cross-module compile error.

All existing 173 tests stay green; the suite runs via `latte test`.

## Risks

- **Import collisions / qualification.** Two nested types with the same simple name in different packages. Mitigation: collect fully-qualified names; if a future collision surfaces, fall back to fully-qualified references at the call site. Covered by a multi-package nested fixture.
- **Cast safety at the dispatch boundary.** The `(Address) value` casts rely on the parser delivering exactly what the child observer's `finish()` returned. This is guaranteed by the runtime contract and exercised by every round-trip test.
- **Serialization re-embedding cost.** Child renders to a string the parent embeds raw — no re-parse, identical to current collection/map serialization, so no new cost class.
- **Validation gaps.** A nested reference that is neither a clean scalar nor a clean nested type (e.g., a raw `List`, a wildcard, a nested non-record class) must fail with a clear message, not slip through. Covered by rejection fixtures.

## Alternatives considered

- **Transitive auto-generation** (generate companions for un-annotated reachable records). More ergonomic for deep graphs, but raises ownership questions (which package owns the generated companion, types you don't control) and interacts awkwardly with the same-module rule. Deferred; explicit `@JSON` is the v1 contract.
- **Inlining the child's per-field builder calls into the parent** instead of calling the child's `toJSON`. More generated code, more coupling, no benefit over raw-embedding the child's existing static method. Rejected.
