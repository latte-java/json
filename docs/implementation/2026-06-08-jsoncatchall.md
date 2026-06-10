# @JSONCatchAll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen so a record may declare one `@JSONCatchAll Map<String, Object>` component that captures every unknown JSON key on deserialize and spreads its entries as top-level keys on serialize.

**Architecture:** A new runtime `any(...)` writer on `JSONBuilder`/`JSONArrayBuilder` serializes arbitrary natural-shape `Object` values. The processor detects the catch-all component, exempts it from typed codegen, and the templates: pre-initialize the catch-all map field, route the observer's `default` arms (and `beginObject`/`beginArray` fall-throughs) into it on deserialize, and spread its entries via `any(...)` on serialize. Deserialize reuses the existing `AnyObjectObserver`/`AnyArrayObserver`.

**Tech Stack:** Java 25, JTE 3.2.1 templates, `javax.annotation.processing`, Latte build (`latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-08-jsoncatchall-design.md`

---

## Notes (read first)

- **Catch-all = one `Map<String, Object>` record component annotated `@JSONCatchAll`.** It is excluded from typed serialize/deserialize codegen but keeps its field declaration (pre-initialized to a `LinkedHashMap`) and its `finish()` constructor arg.
- **Two builders already ship** in `<module>.internal` (they're in `JSONProcessor.HELPERS`), so adding `any(...)` to them needs no emission-list change.
- **`catchAll()` empty ⇒ no change.** Every template branch is guarded so a type without a catch-all is byte-identical to today.
- **Natural shapes** (what `AnyObjectObserver` captures): `String`/`Long`/`BigInteger`/`BigDecimal`/`Boolean`/`null`/`LinkedHashMap`/`ArrayList`.

---

## File Structure

**Create:**
- `src/test/resources/fixtures/catchall/` + reject fixtures.
- `src/test/java/org/lattejava/json/tests/processor/CatchAllCodegenTest.java`, `CatchAllRejectionTest.java`.

**Modify:**
- `src/main/java/org/lattejava/json/JSONBuilder.java` — `any(String, Object)`.
- `src/main/java/org/lattejava/json/JSONArrayBuilder.java` — `any(Object)`.
- `src/test/java/org/lattejava/json/tests/JSONBuilderTest.java`, `JSONArrayBuilderTest.java` — `any` unit tests.
- `src/main/java/org/lattejava/json/jte/Component.java` — `isCatchAll()`.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — `catchAll()`, `typedComponents()`; `collectionComponents()` excludes the catch-all.
- `src/main/jte/companion.jte` — pre-init the catch-all field; conditional `Any*Observer` imports; builder iterates `typedComponents()` + spread.
- `src/main/jte/observerBody.jte` — iterate `typedComponents()`; catch-all `default` arms; `beginObject`/`beginArray` catch-all fall-throughs.
- `src/main/jte/defaultArm.jte` — catch-all-aware default (gains a `value` param).
- `src/main/java/org/lattejava/json/JSONProcessor.java` — detect/exempt/validate the catch-all.

**Acceptance gate every task:** full suite green — `latte test` (currently 231).

---

## Task 1: The `any(...)` runtime writers

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONBuilder.java`, `JSONArrayBuilder.java`
- Modify: `src/test/java/org/lattejava/json/tests/JSONBuilderTest.java`, `JSONArrayBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/org/lattejava/json/tests/JSONBuilderTest.java`:

```java
  @Test
  public void anyWritesNaturalShapes() {
    String json = new JSONBuilder(false)
        .any("s", "hi")
        .any("n", 42L)
        .any("bi", new java.math.BigInteger("123456789012345678901234567890"))
        .any("d", new java.math.BigDecimal("1.5"))
        .any("b", Boolean.TRUE)
        .any("z", null)
        .build();
    assertEquals(json,
        "{\"s\":\"hi\",\"n\":42,\"bi\":123456789012345678901234567890,\"d\":1.5,\"b\":true,\"z\":null}");
  }

  @Test
  public void anyRecursesIntoMapAndList() {
    java.util.Map<String, Object> nested = new java.util.LinkedHashMap<>();
    nested.put("k", "v");
    String json = new JSONBuilder(true)
        .any("obj", nested)
        .any("arr", java.util.List.of(1L, true, "x"))
        .build();
    assertEquals(json, "{\"obj\":{\"k\":\"v\"},\"arr\":[1,true,\"x\"]}");
  }

  @Test(expectedExceptions = JSONProcessingException.class)
  public void anyThrowsOnUnsupportedType() {
    new JSONBuilder().any("x", new Object());
  }
```

Add to `src/test/java/org/lattejava/json/tests/JSONArrayBuilderTest.java`:

```java
  @Test
  public void anyWritesNaturalShapesAndNests() {
    String json = new JSONArrayBuilder()
        .any("hi")
        .any(42L)
        .any(null)
        .any(java.util.List.of(1L, 2L))
        .build();
    assertEquals(json, "[\"hi\",42,null,[1,2]]");
  }
```

(`JSONBuilderTest`/`JSONArrayBuilderTest` already `import module org.lattejava.json;`, so `JSONProcessingException` resolves.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=JSONBuilderTest`
Expected: FAIL — `any` does not exist (compilation failure).

- [ ] **Step 3: Add `any` to `JSONBuilder`**

In `src/main/java/org/lattejava/json/JSONBuilder.java`, add this method as the first public method after the constructors (alphabetical — `any` before `array`):

```java
  /**
   * Writes {@code value} at {@code key} as its natural JSON shape, recursing into {@code Map}/{@code List}. Used to
   * spread a {@code @JSONCatchAll} map; throws on a value type outside the natural shapes that the parser produces.
   */
  public JSONBuilder any(String key, Object value) {
    switch (value) {
      case null -> nullValue(key);
      case String s -> string(key, s);
      case Boolean b -> bool(key, b);
      case BigInteger bi -> bigInteger(key, bi);
      case BigDecimal bd -> decimal(key, bd);
      case Double d -> decimal(key, d);
      case Float f -> decimal(key, f);
      case Number n -> integer(key, n);
      case Map<?, ?> m -> {
        JSONBuilder sub = new JSONBuilder(omitNulls);
        for (Map.Entry<?, ?> e : m.entrySet()) {
          sub.any(String.valueOf(e.getKey()), e.getValue());
        }
        object(key, sub.build());
      }
      case List<?> list -> {
        JSONArrayBuilder sub = new JSONArrayBuilder();
        for (Object element : list) {
          sub.any(element);
        }
        array(key, sub.build());
      }
      default -> throw new JSONProcessingException("Unsupported catch-all value type [" + value.getClass() + "]");
    }
    return this;
  }
```

- [ ] **Step 4: Add `any` to `JSONArrayBuilder`**

In `src/main/java/org/lattejava/json/JSONArrayBuilder.java`, add this method as the first public method after the constructor (alphabetical — `any` before `bigInteger`):

```java
  /**
   * Writes {@code value} as the next element at its natural JSON shape, recursing into {@code Map}/{@code List}.
   * Throws on a value type outside the natural shapes that the parser produces.
   */
  public JSONArrayBuilder any(Object value) {
    switch (value) {
      case null -> nullValue();
      case String s -> string(s);
      case Boolean b -> bool(b);
      case BigInteger bi -> bigInteger(bi);
      case BigDecimal bd -> decimal(bd);
      case Double d -> decimal(BigDecimal.valueOf(d));
      case Float f -> decimal(BigDecimal.valueOf(f.doubleValue()));
      case Number n -> integer(n.longValue());
      case Map<?, ?> m -> {
        JSONBuilder sub = new JSONBuilder(true);
        for (Map.Entry<?, ?> e : m.entrySet()) {
          sub.any(String.valueOf(e.getKey()), e.getValue());
        }
        raw(sub.build());
      }
      case List<?> list -> {
        JSONArrayBuilder sub = new JSONArrayBuilder();
        for (Object element : list) {
          sub.any(element);
        }
        raw(sub.build());
      }
      default -> throw new JSONProcessingException("Unsupported catch-all value type [" + value.getClass() + "]");
    }
    return this;
  }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `latte test --test=JSONBuilderTest` then `latte test --test=JSONArrayBuilderTest`
Expected: PASS — all `any` tests green.

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS — 235 tests (231 + 3 + 1), 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONBuilder.java src/main/java/org/lattejava/json/JSONArrayBuilder.java \
        src/test/java/org/lattejava/json/tests/JSONBuilderTest.java \
        src/test/java/org/lattejava/json/tests/JSONArrayBuilderTest.java
git commit -m "feat: add JSONBuilder/JSONArrayBuilder any() for natural-shape Object values

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Catch-all detection + deserialize capture

Detect the catch-all, exempt it from typed codegen, capture unknowns into it on deserialize, and validate. (Serialize still drops the extras until Task 3.)

**Files:**
- Modify: `src/main/java/org/lattejava/json/jte/Component.java`, `CompanionView.java`
- Modify: `src/main/jte/companion.jte`, `observerBody.jte`, `defaultArm.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Create: `src/test/resources/fixtures/catchall/` + `CatchAllCodegenTest.java`; reject fixtures + `CatchAllRejectionTest.java`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/catchall/module-info.java`:

```java
module demo.catchall {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/catchall/demo/Response.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Response(String id, int code, @JSONCatchAll Map<String, Object> extras) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/CatchAllCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class CatchAllCodegenTest {
  static ProcessorHarness.Result catchall;

  @BeforeClass
  public void compileOnce() throws Exception {
    catchall = ProcessorHarness.compile("catchall");
    assertTrue(catchall.success(), catchall.diagnostics().toString());
  }

  @Test
  public void capturesUnknownsAtNaturalShapes() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Response");
      Class<?> j = loader.loadClass("demo.internal.ResponseJSON");
      String json = "{\"id\":\"a\",\"code\":7,\"s\":\"x\",\"n\":42,\"b\":true,\"z\":null,"
          + "\"obj\":{\"k\":\"v\"},\"arr\":[1,2]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("id").invoke(o), "a");
      assertEquals(t.getMethod("code").invoke(o), 7);
      var extras = (java.util.Map<?, ?>) t.getMethod("extras").invoke(o);
      assertEquals(extras.get("s"), "x");
      assertEquals(extras.get("n"), 42L);
      assertEquals(extras.get("b"), Boolean.TRUE);
      assertTrue(extras.containsKey("z") && extras.get("z") == null, "null entry must be captured");
      assertTrue(extras.get("obj") instanceof java.util.LinkedHashMap, "nested object -> LinkedHashMap");
      assertEquals(((java.util.Map<?, ?>) extras.get("obj")).get("k"), "v");
      assertTrue(extras.get("arr") instanceof java.util.ArrayList, "nested array -> ArrayList");
      assertEquals(((java.util.List<?>) extras.get("arr")), java.util.List.of(1L, 2L));
      // id/code are NOT captured into the catch-all
      assertFalse(extras.containsKey("id"));
      assertFalse(extras.containsKey("code"));
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=CatchAllCodegenTest`
Expected: FAIL — `@BeforeClass` compile fails: the `Map<String, Object>` catch-all component is rejected as an unsupported Map value type.

- [ ] **Step 4: Add `isCatchAll()` to `Component`**

In `src/main/java/org/lattejava/json/jte/Component.java`, add the import (alphabetical with the existing `org.lattejava.json.*` imports):

```java
import org.lattejava.json.InstantFormat;
import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONField;
```

Add a `catchAll` field (alphabetical, before `format`) read in the constructor, and an `isCatchAll()` accessor (alphabetical among the `is*` methods). In the constructor, after `JSONField field = element.getAnnotation(JSONField.class);`, add:

```java
    this.catchAll = element.getAnnotation(JSONCatchAll.class) != null;
```

The field declaration (with the others, alphabetical):

```java
  private final boolean catchAll;
```

The accessor:

```java
  public boolean isCatchAll() {
    return catchAll;
  }
```

- [ ] **Step 5: Add `catchAll()`/`typedComponents()` to `CompanionView` and exclude the catch-all from collections**

In `src/main/java/org/lattejava/json/jte/CompanionView.java`:

Change `collectionComponents()` to exclude the catch-all:

```java
  public List<Component> collectionComponents() {
    return components.stream().filter(c -> c.type().isCollection() && !c.isCatchAll()).toList();
  }
```

Add (alphabetical among the methods — `catchAll` after `companionPackage`, `typedComponents` near the end):

```java
  /** The Java name of the {@code @JSONCatchAll} component, or {@code ""} when the type has none. */
  public String catchAll() {
    return components.stream().filter(Component::isCatchAll).findFirst().map(Component::name).orElse("");
  }
```

```java
  /** The components that participate in typed serialize/deserialize codegen — everything except the catch-all. */
  public List<Component> typedComponents() {
    return components.stream().filter(c -> !c.isCatchAll()).toList();
  }
```

- [ ] **Step 6: Pre-initialize the catch-all field + conditional imports in `companion.jte`**

In `src/main/jte/companion.jte`, **(a)** add the catch-all field initializer in the field-declaration loop. Replace:

```jte
@for(Component c : view.components())
  private @template.declType(type = c.type()) ${c.name()};
@endfor
```

with:

```jte
@for(Component c : view.components())
  private @template.declType(type = c.type()) ${c.name()}@if(c.isCatchAll()) = new java.util.LinkedHashMap<>()@endif;
@endfor
```

**(b)** Add the conditional `Any*Observer` imports. Replace:

```jte
import ${view.qualifiedType()};
import ${view.internalPackage()}.Conversions;
```

with:

```jte
import ${view.qualifiedType()};
@if(!view.catchAll().isEmpty())
import ${view.internalPackage()}.AnyArrayObserver;
import ${view.internalPackage()}.AnyObjectObserver;
@endif
import ${view.internalPackage()}.Conversions;
```

**(c)** In `builder(...)`, change the typed loop to skip the catch-all (the spread comes in Task 3). Replace `@for(Component c : view.components())` (the one inside `builder`, immediately before `@if(c.serialize())`) with `@for(Component c : view.typedComponents())`.

- [ ] **Step 7: Make `defaultArm.jte` catch-all-aware**

Replace `src/main/jte/defaultArm.jte` with:

```jte
@param org.lattejava.json.jte.CompanionView view
@param String value
${!view.catchAll().isEmpty() ? "default -> this." + view.catchAll() + ".put(key, " + value + ");" : view.strict() ? "default -> throw new JSONProcessingException(\"Unknown JSON key [\" + key + \"] for type [" + view.simpleName() + "]\");" : "default -> { /* lenient: ignore unknown key */ }"}
```

- [ ] **Step 8: Route the observer into the catch-all in `observerBody.jte`**

In `src/main/jte/observerBody.jte`:

**(a)** In every per-component callback loop, change `@for(Component c : view.components())` to `@for(Component c : view.typedComponents())` (all ten: string, integer, bigInteger, decimal, bool, nullValue, beginObject, object, beginArray, array).

**(b)** Pass the `value` argument to every `@template.defaultArm(view = view)` call: use `@template.defaultArm(view = view, value = "value")` for `string`/`integer`/`bigInteger`/`decimal`/`bool`/`object`/`array`, and `@template.defaultArm(view = view, value = "null")` for `nullValue`.

**(c)** Replace the `beginObject` trailing throw:

```jte
    }
    throw new IllegalStateException("nested objects unsupported in this release");
  }
```

with:

```jte
    }
@if(view.catchAll().isEmpty())
    throw new IllegalStateException("nested objects unsupported in this release");
@else
    return new AnyObjectObserver();
@endif
  }
```

**(d)** Replace the `beginArray` trailing throw:

```jte
    }
    throw new IllegalStateException("arrays unsupported in this release");
  }
```

with:

```jte
    }
@if(view.catchAll().isEmpty())
    throw new IllegalStateException("arrays unsupported in this release");
@else
    return new AnyArrayObserver();
@endif
  }
```

- [ ] **Step 9: Detect, exempt, and validate the catch-all in `JSONProcessor`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, in `validateComponents`: add a catch-all counter and handle the catch-all at the **top** of the per-component loop (before the wire-key checks), and the >1 check after the loop.

Add `int catchAllCount = 0;` next to `boolean ok = true;`. As the FIRST statement inside `for (RecordComponentElement c : record.getRecordComponents()) {`, add:

```java
      if (c.getAnnotation(JSONCatchAll.class) != null) {
        catchAllCount++;
        TypeView ca = new TypeView(processingEnv, c.asType());
        if (!ca.isMap() || ca.key() == null || !ca.key().name().equals("java.lang.String")
            || ca.value() == null || !ca.value().name().equals("java.lang.Object")) {
          error(c, "@JSONCatchAll component [" + c.getSimpleName() + "] must be of type Map<String, Object>");
          ok = false;
        }
        if (c.getAnnotation(JSONField.class) != null) {
          error(c, "@JSONCatchAll component [" + c.getSimpleName() + "] cannot also be annotated @JSONField");
          ok = false;
        }
        continue;
      }
```

After the loop (before `return ok;`), add:

```java
    if (catchAllCount > 1) {
      error(record, "type [" + record.getQualifiedName() + "] declares [" + catchAllCount
          + "] @JSONCatchAll components; at most one is allowed");
      ok = false;
    }
```

(`JSONCatchAll`, `JSONField` are in `org.lattejava.json`, the same package — no import needed.)

- [ ] **Step 10: Run the codegen test to verify it passes**

Run: `latte test --test=CatchAllCodegenTest`
Expected: PASS — `capturesUnknownsAtNaturalShapes` green (`id`/`code` parsed; `extras` holds the natural shapes; `id`/`code` not captured).

- [ ] **Step 11: Write the rejection fixtures + test**

`src/test/resources/fixtures/badcatchall_type/module-info.java` → `module demo.badcatchall_type { requires static org.lattejava.json; }`; `demo/WrongType.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record WrongType(@JSONCatchAll Map<String, String> bad) {
}
```

`src/test/resources/fixtures/badcatchall_two/module-info.java` → `module demo.badcatchall_two { requires static org.lattejava.json; }`; `demo/Two.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Two(@JSONCatchAll Map<String, Object> a, @JSONCatchAll Map<String, Object> b) {
}
```

`src/test/resources/fixtures/badcatchall_field/module-info.java` → `module demo.badcatchall_field { requires static org.lattejava.json; }`; `demo/WithField.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record WithField(@JSONCatchAll @JSONField(name = "x") Map<String, Object> m) {
}
```

`src/test/java/org/lattejava/json/tests/processor/CatchAllRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class CatchAllRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void wrongTypeRejected() throws Exception {
    assertFailsWith("badcatchall_type", "Map<String, Object>", "[bad]");
  }

  @Test public void twoCatchAllsRejected() throws Exception {
    assertFailsWith("badcatchall_two", "at most one", "@JSONCatchAll");
  }

  @Test public void catchAllWithFieldRejected() throws Exception {
    assertFailsWith("badcatchall_field", "cannot also be annotated @JSONField", "[m]");
  }
}
```

- [ ] **Step 12: Run the rejection test, then the full suite**

Run: `latte test --test=CatchAllRejectionTest`
Expected: PASS — 3 tests green.

Run: `latte test`
Expected: PASS — 239 tests (235 + 1 codegen + 3 rejection), 0 failures. Non-catch-all types are unchanged (`catchAll()` is `""` everywhere, so `defaultArm` keeps lenient/strict and the `beginObject`/`beginArray` throws are unchanged).

- [ ] **Step 13: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/Component.java src/main/java/org/lattejava/json/jte/CompanionView.java \
        src/main/jte/companion.jte src/main/jte/observerBody.jte src/main/jte/defaultArm.jte \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/catchall src/test/resources/fixtures/badcatchall_type \
        src/test/resources/fixtures/badcatchall_two src/test/resources/fixtures/badcatchall_field \
        src/test/java/org/lattejava/json/tests/processor/CatchAllCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/CatchAllRejectionTest.java
git commit -m "feat: @JSONCatchAll captures unknown keys on deserialize

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Catch-all serialize spread

Spread the catch-all map's entries as top-level keys in `toJSON`, completing the round-trip.

**Files:**
- Modify: `src/main/jte/companion.jte`
- Modify: `src/test/java/org/lattejava/json/tests/processor/CatchAllCodegenTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CatchAllCodegenTest`:

```java
  @Test
  public void spreadsAndRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Response");
      Class<?> j = loader.loadClass("demo.internal.ResponseJSON");
      String json = "{\"id\":\"a\",\"code\":7,\"s\":\"x\",\"n\":42,\"b\":true,"
          + "\"obj\":{\"k\":\"v\"},\"arr\":[1,2]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      // named fields first, then the catch-all entries spread at top level in insertion order
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void emptyCatchAllAddsNoKeys() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Response");
      Class<?> j = loader.loadClass("demo.internal.ResponseJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"code\":7}");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"code\":7}");
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=CatchAllCodegenTest`
Expected: FAIL — `spreadsAndRoundTrips` fails: `toJSON` omits the catch-all entries (only `{"id":"a","code":7}`), so it doesn't equal the input.

- [ ] **Step 3: Spread the catch-all in `companion.jte` builder**

In `src/main/jte/companion.jte`, replace the entire `builder(...)` method:

```jte
  private static JSONBuilder builder(${view.simpleName()} value) {
    return new JSONBuilder(${view.omitNulls()})
@if(!view.discriminatorKey().isEmpty())
        .string("${view.discriminatorKey()}", "${view.discriminatorValue()}")
@endif
@for(Component c : view.typedComponents())
@if(c.serialize())
@if(c.isFormatted())
        .string("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.formatterField()}.format(value.${c.name()}()))
@elseif(c.isEpochInstant())
        .integer("${c.wireKey()}", value.${c.name()}() == null ? null : value.${c.name()}().${c.epochMethod()}())
@elseif(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.wireKey() + "\"", val = "value." + c.name() + "()")
@endif
@endif
@endfor
        ;
  }
```

with:

```jte
  private static JSONBuilder builder(${view.simpleName()} value) {
    JSONBuilder b = new JSONBuilder(${view.omitNulls()})
@if(!view.discriminatorKey().isEmpty())
        .string("${view.discriminatorKey()}", "${view.discriminatorValue()}")
@endif
@for(Component c : view.typedComponents())
@if(c.serialize())
@if(c.isFormatted())
        .string("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.formatterField()}.format(value.${c.name()}()))
@elseif(c.isEpochInstant())
        .integer("${c.wireKey()}", value.${c.name()}() == null ? null : value.${c.name()}().${c.epochMethod()}())
@elseif(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.wireKey() + "\"", val = "value." + c.name() + "()")
@endif
@endif
@endfor
        ;
@if(!view.catchAll().isEmpty())
    for (var entry : value.${view.catchAll()}().entrySet()) {
      b.any(entry.getKey(), entry.getValue());
    }
@endif
    return b;
  }
```

(The only changes: `return new JSONBuilder(...)` → `JSONBuilder b = new JSONBuilder(...)`, the conditional spread loop, and `return b;`. For a non-catch-all type this just introduces a local `b` — same output.)

- [ ] **Step 4: Run the codegen tests to verify they pass**

Run: `latte test --test=CatchAllCodegenTest`
Expected: PASS — `spreadsAndRoundTrips` and `emptyCatchAllAddsNoKeys` green, plus the Task-2 capture test.

- [ ] **Step 5: Run the full suite**

Run: `latte test`
Expected: PASS — 241 tests (239 + 2), 0 failures. Every existing companion still round-trips: the `JSONBuilder b = …; return b;` restructuring is behavior-preserving (no catch-all ⇒ no spread).

- [ ] **Step 6: Commit**

```bash
git add src/main/jte/companion.jte src/test/java/org/lattejava/json/tests/processor/CatchAllCodegenTest.java
git commit -m "feat: @JSONCatchAll spreads captured entries on serialize (round-trip)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full suite + spot-check**

Run: `latte test`
Expected: PASS — 241 tests, 0 failures. Confirm green: `CatchAllCodegenTest`, `CatchAllRejectionTest`, `JSONBuilderTest`, `JSONArrayBuilderTest`, plus the prior `SimpleRecordCodegenTest`, `MapCodegenTest`, `PolyCodegenTest`, `UnknownKeyPolicyTest` (these prove the no-catch-all default arms and the `builder` restructuring are unchanged in behavior).

- [ ] **Step 2: Spot-check the generated companion**

Run: `find build/test/generated/catchall -name 'ResponseJSON.java' -exec cat {} \;`
Expected: `private Map<String, Object> extras = new java.util.LinkedHashMap<>();`; the `default` arms `this.extras.put(key, value)` / `put(key, null)`; `beginObject`/`beginArray` ending with `return new AnyObjectObserver();` / `return new AnyArrayObserver();`; `builder(...)` spreading `b.any(entry.getKey(), entry.getValue())`; and `id`/`code` handled normally (not in the catch-all).

- [ ] **Step 3: No commit** (verification only). If any check fails, surface it to the reviewer rather than patching silently.

---

## Self-Review

**Spec coverage:**
- §1 detection (`catchAll()`/`typedComponents()`/`isCatchAll()`; exempt from validation) → Task 2.
- §2 deserialize capture (default arms, `beginObject`/`beginArray`, pre-init field, natural shapes) → Task 2.
- §3 serialize spread → Task 3.
- §4 `any(...)` runtime writers → Task 1.
- §5 validation (wrong type, two catch-alls, +`@JSONField`) → Task 2.
- §Testing → Task 1 (`any` units), Task 2 (capture + 3 rejections), Task 3 (spread + round-trip + empty).

**Placeholder scan:** none — every step is complete code or an exact before/after.

**Type consistency:** `JSONBuilder.any(String, Object)` / `JSONArrayBuilder.any(Object)` (Task 1) used by `companion.jte` spread (Task 3). `Component.isCatchAll()` (Task 2) used by `CompanionView.catchAll()/typedComponents()/collectionComponents()` (Task 2) and `companion.jte` field init (Task 2). `CompanionView.catchAll()` (Task 2) used by `defaultArm.jte`, `observerBody.jte`, and `companion.jte` (Tasks 2-3). `defaultArm.jte` gains a `value` param (Task 2 Step 7) consumed at every call site (Step 8). Test counts: 231 → 235 (T1) → 239 (T2) → 241 (T3).
