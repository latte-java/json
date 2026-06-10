# Dynamic `Map<String, Object>` fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a `@JSON` type declare a *named* (non-`@JSONCatchAll`) member typed `Map<String, Object>` that round-trips an arbitrary JSON object nested under the member's own wire key.

**Architecture:** Pure annotation-processor codegen change. Deserialize routes the member's nested object through the existing `AnyObjectObserver` runtime; serialize writes it via the existing `JSONBuilder.any(String, Object)` writer. Two new `TypeView` predicates and one `CompanionView` predicate gate the behavior; the validator stops rejecting the shape; three JTE templates branch on it. **No new runtime classes, no public API change, no `module-info` change.**

**Tech Stack:** Java 25 annotation processor, JTE templates (`src/main/jte/*.jte`), TestNG fixture-based codegen tests compiled with real `javac` via `ProcessorHarness`.

---

## Background the implementer needs

- **Companion generation.** Each `@JSON` type `Foo` generates `<pkg>.internal.FooJSON` from `src/main/jte/companion.jte` and its partials (`observerBody.jte`, `mapObserver.jte`, `arrayObserver.jte`, `memberCall.jte`, …). Templates read *facts* from `CompanionView` (the type) and `Component` (one member), and `Component.type()` returns a `TypeView` (facts about the member's Java type). Templates contain no Java string-building logic beyond what those views expose.
- **The runtime helpers already exist.** `AnyArrayObserver`, `AnyObjectObserver`, `JSONArrayBuilder`, `JSONBuilder`, `Conversions`, `Numbers` are in `HelperEmitter.HELPERS` and are emitted **once per compilation, unconditionally**, into the consumer's `<module>.internal` package (`src/main/java/org/lattejava/json/processor/HelperEmitter.java:12`). So `AnyObjectObserver` is always present even when no `@JSONCatchAll` exists — only the *import line* in `companion.jte` is currently gated on the catch-all. This is why the dynamic-map change needs only an import-guard widening, not new helper emission.
- **How catch-all reads arbitrary objects today.** In `observerBody.jte`, when a catch-all is present, `beginObject` falls through to `return new AnyObjectObserver();` and the finished `LinkedHashMap` lands via the `default` arm of `object(String, Object)`. We reuse the same `AnyObjectObserver`, but dispatched by the member's *known* key instead of the default arm.
- **How catch-all writes arbitrary values today.** `companion.jte` spreads catch-all entries with `b.any(key, value)`. `JSONBuilder.any` (`src/main/java/org/lattejava/json/JSONBuilder.java:43`) dispatches on the runtime value type, recurses into nested `Map`/`List`, and respects `omitNulls` (null → `nullValue(key)` → omitted when `omitNulls=true`). We reuse it verbatim.
- **Test harness.** `ProcessorHarness.compile("<fixture>")` compiles `src/test/resources/fixtures/<fixture>/` with the processor attached and returns `success()`, `diagnostics()`, and a `loader()` for reflectively invoking generated companions. See `MapCodegenTest` and `CatchAllCodegenTest` for the exact idiom.
- **Run a single test:** `latte test --test=<ClassName>` (e.g. `latte test --test=DynamicMapCodegenTest`). Requires Java 25 on the PATH.

## File structure

**Production (modify):**
- `src/main/java/org/lattejava/json/jte/TypeView.java` — add `isObject()` and `isDynamicMap()`.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — add `hasDynamicMap()`.
- `src/main/java/org/lattejava/json/processor/AbstractValidator.java` — accept `Map<String, Object>` (String key) as a dynamic map in `validateType`.
- `src/main/jte/observerBody.jte` — route a dynamic-map member's `beginObject` to `new AnyObjectObserver()`.
- `src/main/jte/companion.jte` — widen the `Any*Observer` import guard to include dynamic maps.
- `src/main/jte/mapObserver.jte` — for a dynamic map, emit only an `any`-based `<name>ToJSON` serializer and skip the typed `<Name>MapObserver`.

**Tests (create):**
- `src/test/resources/fixtures/dynamicmap/module-info.java`
- `src/test/resources/fixtures/dynamicmap/demo/Settings.java` (default `omitNulls`)
- `src/test/resources/fixtures/dynamicmap/demo/Keep.java` (`omitNulls = false`)
- `src/test/resources/fixtures/dynamicmap/demo/Mixed.java` (dynamic map + `@JSONCatchAll`)
- `src/test/resources/fixtures/dynamicmap/demo/Snake.java` (`SNAKE_CASE` naming)
- `src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java`
- `src/test/resources/fixtures/baddynamicmap/module-info.java`
- `src/test/resources/fixtures/baddynamicmap/demo/BadKey.java` (`Map<Integer, Object>`)
- `src/test/java/org/lattejava/json/tests/processor/DynamicMapRejectionTest.java`

---

## Task 1: Core dynamic-map support (read + write round-trip)

This is the vertical slice: the fixture, the two `TypeView` predicates, the validator acceptance, and the three template edits. All are required before the round-trip test can pass, so they land together.

**Files:**
- Create: `src/test/resources/fixtures/dynamicmap/module-info.java`
- Create: `src/test/resources/fixtures/dynamicmap/demo/Settings.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java`
- Modify: `src/main/java/org/lattejava/json/jte/TypeView.java`
- Modify: `src/main/java/org/lattejava/json/processor/AbstractValidator.java:162-182`
- Modify: `src/main/jte/observerBody.jte:73-88`
- Modify: `src/main/jte/companion.jte:12-15`
- Modify: `src/main/jte/mapObserver.jte`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/dynamicmap/module-info.java`:

```java
module demo.dynamicmap {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/dynamicmap/demo/Settings.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Settings(String id, Map<String, Object> prefs) {
}
```

- [ ] **Step 2: Write the failing round-trip test**

`src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DynamicMapCodegenTest {
  static ProcessorHarness.Result dyn;

  @BeforeClass
  public void compileOnce() throws Exception {
    dyn = ProcessorHarness.compile("dynamicmap");
    assertTrue(dyn.success(), dyn.diagnostics().toString());
  }

  @Test
  public void capturesNaturalShapesAndRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      String json = "{\"id\":\"a\",\"prefs\":{\"s\":\"x\",\"n\":42,\"b\":true,"
          + "\"obj\":{\"k\":\"v\"},\"arr\":[1,2]}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("id").invoke(o), "a");
      var prefs = (java.util.Map<?, ?>) t.getMethod("prefs").invoke(o);
      assertEquals(prefs.get("s"), "x");
      assertEquals(prefs.get("n"), 42L);
      assertEquals(prefs.get("b"), Boolean.TRUE);
      assertTrue(prefs.get("obj") instanceof java.util.LinkedHashMap, "nested object -> LinkedHashMap");
      assertEquals(((java.util.Map<?, ?>) prefs.get("obj")).get("k"), "v");
      assertTrue(prefs.get("arr") instanceof java.util.ArrayList, "nested array -> ArrayList");
      assertEquals(((java.util.List<?>) prefs.get("arr")), java.util.List.of(1L, 2L));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void preservesInsertionOrder() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class)
          .invoke(null, "{\"id\":\"a\",\"prefs\":{\"z\":1,\"y\":2,\"x\":3}}");
      var prefs = (java.util.Map<?, ?>) t.getMethod("prefs").invoke(o);
      assertEquals(new java.util.ArrayList<>(prefs.keySet()), java.util.List.of("z", "y", "x"));
    }
  }

  @Test
  public void emptyDynamicMapRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"prefs\":{}}");
      assertTrue(((java.util.Map<?, ?>) t.getMethod("prefs").invoke(o)).isEmpty());
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"prefs\":{}}");
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=DynamicMapCodegenTest`
Expected: FAIL — `compileOnce` fails because the processor currently rejects `Map<String, Object>` with a diagnostic like `@JSON member [prefs] has an unsupported Map value type [java.lang.Object]` (so `dyn.success()` is `false` and the `@BeforeClass` assert trips).

- [ ] **Step 4: Add the `TypeView` predicates**

In `src/main/java/org/lattejava/json/jte/TypeView.java`, add `isDynamicMap()` after `isCollection()` (alphabetical: `isCollection`, `isDynamicMap`, `isEnum`):

```java
  /**
   * Whether this type is a dynamic map — {@code Map<String, Object>} — whose values are arbitrary JSON captured at
   * their natural Java shapes. Read through {@code AnyObjectObserver}, written through {@code JSONBuilder.any}.
   */
  public boolean isDynamicMap() {
    return isMap() && key() != null && key().isString() && value() != null && value().isObject();
  }
```

and add `isObject()` after `isNumeric()` (alphabetical: `isNumeric`, `isObject`, `isPolymorphic`):

```java
  public boolean isObject() {
    return name().equals("java.lang.Object");
  }
```

- [ ] **Step 5: Accept the dynamic-map shape in the validator**

In `src/main/java/org/lattejava/json/processor/AbstractValidator.java`, inside `validateType`'s `mt.isMap()` block, add the dynamic-map acceptance immediately **after** the Map-key check and **before** the nested-collection check. The key check already rejects non-`String`-form keys, so a `Map<Integer, Object>` still errors there. The new lines:

```java
        if (k.isString() && v != null && v.isObject()) {
          return true;
        }
```

For reference, the block becomes:

```java
      if (mt.isMap()) {
        TypeView k = mt.key();
        TypeView v = mt.value();
        if (k == null || !k.isStringForm()) {
          error(at, "@JSON member [" + name + "] has an unsupported Map key type ["
              + (k == null ? "?" : k.name()) + "] (Map key must be String, UUID, an enum, or a java.time type)");
          return false;
        }
        if (k.isString() && v != null && v.isObject()) {
          return true;
        }
        if (v == null || v.isCollection()) {
          error(at, "@JSON member [" + name + "] uses a nested collection as a Map value ["
              + (v == null ? "?" : v.name()) + "] which is not supported in this release");
          return false;
        }
        if (!isSupportedComponentType(v)) {
          error(at, v.isRecord() && !v.isNested() ? notJSON(at, v)
              : "@JSON member [" + name + "] has an unsupported Map value type [" + v.name() + "]");
          return false;
        }
        return true;
      }
```

- [ ] **Step 6: Route the dynamic-map `beginObject` to `AnyObjectObserver`**

In `src/main/jte/observerBody.jte`, in the `beginObject(String key)` switch (currently lines 73-81), add a dynamic-map arm **before** the generic `isMap()` arm so it wins:

```jte
  @Override public JSONObjectHandler beginObject(String key) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.type().isDynamicMap())
      case "${c.wireKey()}" -> { return new AnyObjectObserver(); }
@elseif(c.deserialize() && c.type().isMap())
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())MapObserver(); }
@elseif(c.deserialize() && c.type().hasCompanion())
      case "${c.wireKey()}" -> { return new ${c.type().nestedCompanion()}(); }
@endif
@endfor
    }
@if(view.catchAll().isEmpty())
    throw new IllegalStateException("nested objects unsupported in this release");
@else
    return new AnyObjectObserver();
@endif
  }
```

No change is needed to the `object(String key, Object value)` arm below it: its condition `c.type().isMap() || c.type().hasCompanion()` already matches a dynamic map, so the finished `LinkedHashMap` is cast and assigned by the existing `case "${c.wireKey()}" -> this.${c.name()} = (...) value;` line (`declType` renders `Map<String, Object>`).

- [ ] **Step 7: Widen the `Any*Observer` import guard**

In `src/main/jte/companion.jte`, change the import guard (currently lines 12-15) from `@if(!view.catchAll().isEmpty())` to also include dynamic maps:

```jte
@if(!view.catchAll().isEmpty() || view.hasDynamicMap())
import ${view.internalPackage()}.AnyArrayObserver;
import ${view.internalPackage()}.AnyObjectObserver;
@endif
```

(`view.hasDynamicMap()` is added in Step 8. The serialize call site for a `Map` member — `.object("${c.wireKey()}", value.${c.read()} == null ? null : ${c.name()}ToJSON(value.${c.read()}))` on line 66 — needs **no** change; only the generated `${c.name()}ToJSON` body differs, handled in Step 9.)

- [ ] **Step 8: Add `hasDynamicMap()` to `CompanionView`**

In `src/main/java/org/lattejava/json/jte/CompanionView.java`, add after `enumImports()` (alphabetical: `enumImports`, `hasDynamicMap`, `internalPackage`):

```java
  /** Whether any non-catch-all member is a dynamic {@code Map<String, Object>} (gates the {@code Any*Observer} imports). */
  public boolean hasDynamicMap() {
    return components.stream().anyMatch(c -> c.type().isDynamicMap());
  }
```

- [ ] **Step 9: Generate the `any`-based serializer for dynamic maps**

In `src/main/jte/mapObserver.jte`, wrap the existing body so a dynamic map emits only a small `any`-based serializer (no typed `<Name>MapObserver`, which exists only to read typed values). The existing typed body is preserved unchanged in the `@else`:

```jte
@import org.lattejava.json.jte.Component
@param Component c
@param boolean omitNulls
@if(c.type().isDynamicMap())
  private static String ${c.name()}ToJSON(@template.declType(type = c.type()) v) {
    var b = new JSONBuilder(${omitNulls});
    for (var en : v.entrySet()) b.any(en.getKey(), en.getValue());
    return b.build();
  }
@else
!{var keyType = c.type().key();}
!{var valType = c.type().value();}
!{var mapKey = keyType.isEnum() ? "en.getKey().name()" : keyType.isString() ? "en.getKey()" : "en.getKey().toString()";}
!{var stub = "throw new JSONProcessingException(\"unexpected JSON value for Map value type [" + valType.name() + "]\");";}
  private static String ${c.name()}ToJSON(@template.declType(type = c.type()) v) {
    var b = new JSONBuilder(${omitNulls});
    for (var en : v.entrySet()) b.@template.memberCall(type = valType, key = mapKey, val = "en.getValue()");
    return b.build();
  }
  private static final class @template.cap(name = c.name())MapObserver implements JSONObserver<@template.declType(type = c.type())> {
    private final @template.declType(type = c.type()) map = new java.util.LinkedHashMap<>();
@if(valType.hasCompanion())
    @Override public void string(String key, String value) { ${stub} }
    @Override public void integer(String key, long value) { ${stub} }
    @Override public void bigInteger(String key, java.math.BigInteger value) { ${stub} }
    @Override public void decimal(String key, java.math.BigDecimal value) { ${stub} }
    @Override public void bool(String key, boolean value) { ${stub} }
@elseif(valType.isStringForm())
    @Override public void string(String key, String value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.fromString(type = valType, expr = "value")); }
    @Override public void integer(String key, long value) { ${stub} }
    @Override public void bigInteger(String key, java.math.BigInteger value) { ${stub} }
    @Override public void decimal(String key, java.math.BigDecimal value) { ${stub} }
    @Override public void bool(String key, boolean value) { ${stub} }
@elseif(valType.isBool())
    @Override public void string(String key, String value) { ${stub} }
    @Override public void integer(String key, long value) { ${stub} }
    @Override public void bigInteger(String key, java.math.BigInteger value) { ${stub} }
    @Override public void decimal(String key, java.math.BigDecimal value) { ${stub} }
    @Override public void bool(String key, boolean value) { map.put(@template.fromString(type = keyType, expr = "key"), value); }
@else
    @Override public void string(String key, String value) { ${stub} }
    @Override public void integer(String key, long value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.narrow(type = valType, source = "integer")); }
    @Override public void bigInteger(String key, java.math.BigInteger value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.narrow(type = valType, source = "bigInteger")); }
    @Override public void decimal(String key, java.math.BigDecimal value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.narrow(type = valType, source = "decimal")); }
    @Override public void bool(String key, boolean value) { ${stub} }
@endif
    @Override public void nullValue(String key) { map.put(@template.fromString(type = keyType, expr = "key"), null); }
    @Override public @template.declType(type = c.type()) finish() { return map; }
@if(valType.hasCompanion())
    @Override public JSONObjectHandler beginObject(String key) { return new ${valType.nestedCompanion()}(); }
    @Override public JSONArrayObserver<?> beginArray(String key) { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(String key, Object value) { map.put(@template.fromString(type = keyType, expr = "key"), (${valType.decl()}) value); }
    @Override public void array(String key, Object value) {}
@else
    @Override public JSONObjectHandler beginObject(String key) { throw new JSONProcessingException("nested objects in collections unsupported"); }
    @Override public JSONArrayObserver<?> beginArray(String key) { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(String key, Object value) {}
    @Override public void array(String key, Object value) {}
@endif
  }
@endif
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `latte test --test=DynamicMapCodegenTest`
Expected: PASS — all three test methods green; `compileOnce` succeeds.

- [ ] **Step 11: Run the full suite to check for regressions**

Run: `latte test`
Expected: PASS — all pre-existing tests stay green (no type without a dynamic map changes; `hasDynamicMap()` is `false` and `isDynamicMap()` is `false` everywhere else, so every existing import guard and template path is unchanged).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/TypeView.java \
        src/main/java/org/lattejava/json/jte/CompanionView.java \
        src/main/java/org/lattejava/json/processor/AbstractValidator.java \
        src/main/jte/observerBody.jte src/main/jte/companion.jte src/main/jte/mapObserver.jte \
        src/test/resources/fixtures/dynamicmap \
        src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java
git commit -m "feat: Support dynamic Map<String, Object> fields (arbitrary JSON values)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `omitNulls` behavior for dynamic-map entries

Verifies that a null *entry* honors the type's `omitNulls` (dropped under the `true` default, written under `false`), and that nested maps/lists inherit the same flag. This exercises the `new JSONBuilder(${omitNulls})` value passed into the generated serializer — a path the Task 1 happy-path does not cover.

**Files:**
- Create: `src/test/resources/fixtures/dynamicmap/demo/Keep.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java`

- [ ] **Step 1: Add the `omitNulls = false` fixture**

`src/test/resources/fixtures/dynamicmap/demo/Keep.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON(omitNulls = false)
public record Keep(Map<String, Object> data) {
}
```

- [ ] **Step 2: Add the failing tests**

Append to `DynamicMapCodegenTest`:

```java
  @Test
  public void omitNullsTrueDropsNullEntryOnSerialize() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");      // omitNulls defaults to true
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class)
          .invoke(null, "{\"id\":\"a\",\"prefs\":{\"k\":null,\"j\":1}}");
      var prefs = (java.util.Map<?, ?>) t.getMethod("prefs").invoke(o);
      assertTrue(prefs.containsKey("k") && prefs.get("k") == null, "null entry captured on read");
      // serialize drops the null entry under omitNulls=true
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"prefs\":{\"j\":1}}");
    }
  }

  @Test
  public void omitNullsFalseKeepsNullEntryAndNesting() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Keep");
      Class<?> j = loader.loadClass("demo.internal.KeepJSON");
      // null entry at top level and inside a nested object both survive under omitNulls=false
      String json = "{\"data\":{\"k\":null,\"obj\":{\"q\":null}}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the test to verify status**

Run: `latte test --test=DynamicMapCodegenTest`
Expected: PASS — Task 1 already implemented this behavior (the generated `dataToJSON` is built with `new JSONBuilder(false)` and `JSONBuilder.any` recurses with the same flag). These are regression-locking tests. If `omitNullsFalseKeepsNullEntryAndNesting` fails, confirm Step 9 of Task 1 passed `omitNulls` into the dynamic-map serializer's `new JSONBuilder(${omitNulls})`.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/fixtures/dynamicmap/demo/Keep.java \
        src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java
git commit -m "test: Dynamic map honors omitNulls for null entries

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Whole-map-null field + naming strategy

Verifies (a) a null *whole map* follows the normal field convention via the `value.read == null ? null : ...` guard, and (b) the member serializes/deserializes under its naming-strategy-applied wire key.

**Files:**
- Create: `src/test/resources/fixtures/dynamicmap/demo/Snake.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java`

- [ ] **Step 1: Add the snake_case fixture**

`src/test/resources/fixtures/dynamicmap/demo/Snake.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Snake(Map<String, Object> userPrefs) {
}
```

- [ ] **Step 2: Add the failing tests**

Append to `DynamicMapCodegenTest`:

```java
  @Test
  public void wholeMapNullOmittedUnderOmitNullsTrue() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");      // omitNulls=true
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\"}");
      assertNull(t.getMethod("prefs").invoke(o), "absent map field is null");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\"}");
    }
  }

  @Test
  public void wholeMapNullWrittenUnderOmitNullsFalse() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Keep");          // omitNulls=false
      Class<?> j = loader.loadClass("demo.internal.KeepJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{}");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"data\":null}");
    }
  }

  @Test
  public void usesNamingStrategyWireKey() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Snake");
      Class<?> j = loader.loadClass("demo.internal.SnakeJSON");
      String json = "{\"user_prefs\":{\"a\":1}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var prefs = (java.util.Map<?, ?>) t.getMethod("userPrefs").invoke(o);
      assertEquals(prefs.get("a"), 1L);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the test to verify status**

Run: `latte test --test=DynamicMapCodegenTest`
Expected: PASS — Task 1 already routes by `c.wireKey()` (naming-applied) and guards the serialize call site on `value.${c.read()} == null`. Regression-locking tests.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/fixtures/dynamicmap/demo/Snake.java \
        src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java
git commit -m "test: Dynamic map null-field and naming-strategy behavior

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Coexistence with `@JSONCatchAll`

Verifies a single type can carry both a dynamic-map member (a *known* key, nested under its name) and a `@JSONCatchAll` (the *unknown*-key bucket, spread at top level). Also exercises the import guard with both triggers present.

**Files:**
- Create: `src/test/resources/fixtures/dynamicmap/demo/Mixed.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java`

- [ ] **Step 1: Add the coexistence fixture**

`src/test/resources/fixtures/dynamicmap/demo/Mixed.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Mixed(Map<String, Object> meta, @JSONCatchAll Map<String, Object> extras) {
}
```

- [ ] **Step 2: Add the failing test**

Append to `DynamicMapCodegenTest`:

```java
  @Test
  public void coexistsWithCatchAll() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Mixed");
      Class<?> j = loader.loadClass("demo.internal.MixedJSON");
      // "meta" is the known dynamic-map key (nested); "x" and "y" are unknown -> catch-all (spread)
      String json = "{\"meta\":{\"a\":1},\"x\":7,\"y\":\"z\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var meta = (java.util.Map<?, ?>) t.getMethod("meta").invoke(o);
      assertEquals(meta.get("a"), 1L);
      var extras = (java.util.Map<?, ?>) t.getMethod("extras").invoke(o);
      assertEquals(extras.get("x"), 7L);
      assertEquals(extras.get("y"), "z");
      assertFalse(extras.containsKey("meta"), "known dynamic-map key must not leak into the catch-all");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the test to verify status**

Run: `latte test --test=DynamicMapCodegenTest`
Expected: PASS — the `beginObject` switch dispatches the known `meta` key to `AnyObjectObserver` before any default arm; unknown keys still fall to the catch-all default arm. Regression-locking test.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/fixtures/dynamicmap/demo/Mixed.java \
        src/test/java/org/lattejava/json/tests/processor/DynamicMapCodegenTest.java
git commit -m "test: Dynamic map coexists with @JSONCatchAll

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Non-`String` key with `Object` value stays a compile error

Confirms the non-goal: `Map<Integer, Object>` (and any non-`String` key) is still rejected, so the dynamic-map acceptance did not widen the contract beyond `Map<String, Object>`.

**Files:**
- Create: `src/test/resources/fixtures/baddynamicmap/module-info.java`
- Create: `src/test/resources/fixtures/baddynamicmap/demo/BadKey.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/DynamicMapRejectionTest.java`

- [ ] **Step 1: Add the rejection fixture**

`src/test/resources/fixtures/baddynamicmap/module-info.java`:

```java
module demo.baddynamicmap {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/baddynamicmap/demo/BadKey.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadKey(Map<Integer, Object> m) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/DynamicMapRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DynamicMapRejectionTest {
  @Test
  public void nonStringKeyWithObjectValueRejected() throws Exception {
    var r = ProcessorHarness.compile("baddynamicmap");
    assertFalse(r.success(), "Map<Integer, Object> must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("m")),
        "expected Map-key error for [m], got: " + r.diagnostics());
  }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `latte test --test=DynamicMapRejectionTest`
Expected: PASS — the key check (`!k.isStringForm()`) fires before the dynamic-map acceptance, so `Map<Integer, Object>` errors with the existing `unsupported Map key type` message. (This test passes immediately; it pins the non-goal against future regressions.)

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/fixtures/baddynamicmap \
        src/test/java/org/lattejava/json/tests/processor/DynamicMapRejectionTest.java
git commit -m "test: Reject Map<Integer, Object> (dynamic map requires String key)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Final verification + mark design implemented

**Files:**
- Modify: `docs/design/2026-06-10-dynamic-map-fields-design.md`

- [ ] **Step 1: Run the full suite**

Run: `latte test`
Expected: PASS — all pre-existing tests plus the new `DynamicMapCodegenTest` and `DynamicMapRejectionTest` green.

- [ ] **Step 2: Update the design doc status**

In `docs/design/2026-06-10-dynamic-map-fields-design.md`, change the `**Status:**` line to:

```markdown
**Status:** Implemented
```

- [ ] **Step 3: Commit**

```bash
git add docs/design/2026-06-10-dynamic-map-fields-design.md
git commit -m "docs: Mark dynamic Map<String, Object> design as implemented

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** read path (Task 1), write path (Task 1), natural shapes + insertion order (Task 1), empty map (Task 1), `omitNulls` for entries (Task 2), whole-map-null field (Task 3), naming strategy (Task 3), coexistence with `@JSONCatchAll` (Task 4), non-`String`-key rejection (Task 5), full-suite regression (Tasks 1 & 6). All design sections map to a task.
- **Refinement vs. design:** the design mentioned surfacing `isDynamicMap()` on `Component`; the plan instead reads `c.type().isDynamicMap()` directly in the templates (every template already uses `c.type()`), so no `Component` change is needed — one fewer file touched, same behavior.
- **Type consistency:** `isObject()`/`isDynamicMap()` (TypeView), `hasDynamicMap()` (CompanionView), and `${c.name()}ToJSON` (mapObserver, called from companion.jte) are named identically everywhere they appear.
- **Note on "passes immediately" tests:** Tasks 2-5 are largely characterization/regression tests because Task 1 implements the whole vertical slice; each still exercises a distinct code path (omitNulls flag, null-field guard, naming wire key, import guard with both triggers, key-check precedence) and is called out as such rather than presented as red-green.
