# Nested `@JSON` Objects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen so a record component may be a nested `@JSON` record — as a direct field, a `List`/`Set` element, or a `Map` value — and round-trips correctly, including deep and recursive nesting.

**Architecture:** Pure codegen change on the JTE templates plus `JSONProcessor` validation, driving the already-shipped nested-dispatch runtime (`beginObject`→child observer, `object(key,value)`→assign; mirrored on collections/maps). Nested types and their companions are referenced **fully-qualified** (e.g. `demo.Address`, `demo.internal.AddressJSON`), so no new imports and no same-simple-name import collisions. Serialization embeds the child's `toJSON` output raw via existing `JSONBuilder.object(String,String)` / `JSONArrayBuilder.raw(String)`. No public API change, no `module-info` change, no new runtime helper.

**Tech Stack:** Java 25, JTE 3.2.1 templates (`src/main/jte/*.jte`), `javax.annotation.processing`, Latte build (`latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-06-nested-json-objects-design.md`

---

## Notes & deviations from the spec (read first)

- **Spec §6 (imports) → fully-qualified references.** Instead of collecting/deduping nested imports, the generated code references nested types and companions by fully-qualified name. This is strictly simpler and removes the spec's "import collision" risk (two nested types with the same simple name) entirely. `CompanionView`/`Component`/`collectEnums` are unchanged.
- **Spec §1 / test-list (cross-module rejection) → no `getModuleOf` guard.** `@JSON` has `SOURCE` retention, so a nested type from another (compiled) module is not visible as `@JSON` during processing and is rejected as "not @JSON-annotated" by the same code path as a same-module non-`@JSON` record. A dedicated cross-module guard would only be reachable under multi-module compilation (which the harness does not do) and is therefore omitted as dead code. The "not @JSON-annotated" rejection is implemented and tested (Task 2).
- **Existing fixture/test change (required).** Enabling `List<@JSON>` makes `badcollections/demo/JsonElement.java` (`List<Inner>` where `Inner` is `@JSON`) valid. `JsonElement.java` + `Inner.java` are removed and `CollectionRejectionTest.collectionOfJSONElementRejected` is deleted; that scenario becomes a positive test in `NestedCodegenTest` (Task 1). The other three `CollectionRejectionTest` cases (`deep`, `m`, `raw`) are unaffected.
- **Orphan cleanup.** `src/test/resources/fixtures/kitchensinknested/` asserts (in a comment only — no test references it) that nested records are rejected. Its premise is now false; it is deleted in Task 3.

---

## File Structure

**Modify (codegen):**
- `src/main/java/org/lattejava/json/jte/TypeView.java` — add `isNested()`, `isRecord()`, `decl()`, `nestedCompanion()`.
- `src/main/jte/declType.jte` — use `decl()` so nested element/value/field types render fully-qualified.
- `src/main/jte/memberCall.jte` — nested object-member serialization (direct field + Map value).
- `src/main/jte/arrayAppend.jte` — nested element serialization (List/Set).
- `src/main/jte/observerBody.jte` — nested `beginObject`/`object` arms for direct fields.
- `src/main/jte/arrayObserver.jte` — nested element dispatch (replaces the throwing stub).
- `src/main/jte/mapObserver.jte` — nested value dispatch (replaces the throwing stub).
- `src/main/java/org/lattejava/json/JSONProcessor.java` — accept nested in `isSupportedComponentType`; precise "not @JSON-annotated" message in `validateComponents`.

**Create (tests/fixtures):**
- `src/test/resources/fixtures/nested/` — positive fixture (module `demo.nested`): `demo.geo.Geo`, `demo.Address`, `demo.AddressType`, `demo.User`, `demo.Loose`, `demo.Tree`, `module-info.java`.
- `src/test/java/org/lattejava/json/tests/processor/NestedCodegenTest.java` — round-trip tests.
- `src/test/resources/fixtures/badnested/` — rejection fixture (module `demo.badnested`): `demo.Plain`, `demo.HasPlain`, `module-info.java`.
- `src/test/java/org/lattejava/json/tests/processor/NestedRejectionTest.java` — rejection test.

**Delete:**
- `src/test/resources/fixtures/badcollections/demo/JsonElement.java`, `.../Inner.java` (Task 1).
- `src/test/resources/fixtures/kitchensinknested/` (Task 3).

**Acceptance gate for every task:** the full suite green — `latte test` (currently 173 tests). Per-class: `latte test --test=NestedCodegenTest`.

---

## Task 1: Nested `@JSON` objects — all positions

**Files:**
- Create: `src/test/resources/fixtures/nested/module-info.java`, `.../demo/geo/Geo.java`, `.../demo/Address.java`, `.../demo/AddressType.java`, `.../demo/User.java`, `.../demo/Loose.java`, `.../demo/Tree.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/NestedCodegenTest.java`
- Modify: `src/main/java/org/lattejava/json/jte/TypeView.java`
- Modify: `src/main/jte/declType.jte`, `memberCall.jte`, `arrayAppend.jte`, `observerBody.jte`, `arrayObserver.jte`, `mapObserver.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java:165-176` (`isSupportedComponentType`)
- Delete: `src/test/resources/fixtures/badcollections/demo/JsonElement.java`, `.../demo/Inner.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/CollectionRejectionTest.java` (remove `collectionOfJSONElementRejected`)

- [ ] **Step 1: Write the positive fixture**

`src/test/resources/fixtures/nested/module-info.java`:

```java
module demo.nested {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/nested/demo/geo/Geo.java` (cross-package nested type — proves fully-qualified references work across packages in one module):

```java
package demo.geo;

import module org.lattejava.json;

@JSON
public record Geo(double lat, double lng) {
}
```

`src/test/resources/fixtures/nested/demo/AddressType.java`:

```java
package demo;

public enum AddressType {
  HOME, WORK
}
```

`src/test/resources/fixtures/nested/demo/Address.java` (nested field `geo` → deep nesting):

```java
package demo;

import demo.geo.Geo;
import module org.lattejava.json;

@JSON
public record Address(String street, String city, Geo geo) {
}
```

`src/test/resources/fixtures/nested/demo/User.java` (all positions: direct field, List, Set, Map value):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record User(String name, Address address, List<Address> prior, Set<Address> seen,
                   Map<AddressType, Address> byType) {
}
```

`src/test/resources/fixtures/nested/demo/Loose.java` (omit-nulls disabled, for null-nested emission):

```java
package demo;

import module org.lattejava.json;

@JSON(omitNulls = false)
public record Loose(String name, Address address) {
}
```

`src/test/resources/fixtures/nested/demo/Tree.java` (recursion):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Tree(String name, List<Tree> kids) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/NestedCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class NestedCodegenTest {
  static ProcessorHarness.Result nested;

  @BeforeClass
  public void compileOnce() throws Exception {
    nested = ProcessorHarness.compile("nested");
    assertTrue(nested.success(), nested.diagnostics().toString());
  }

  @Test
  public void nestedFieldRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},\"prior\":[],\"seen\":[],\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void listOfNestedRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},"
          + "\"prior\":[{\"street\":\"2 Oak\",\"city\":\"Boulder\",\"geo\":{\"lat\":3.0,\"lng\":4.0}}],"
          + "\"seen\":[],\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void setOfNestedRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},\"prior\":[],"
          + "\"seen\":[{\"street\":\"3 Pine\",\"city\":\"Aspen\",\"geo\":{\"lat\":5.0,\"lng\":6.0}}],"
          + "\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void mapValueNestedRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},\"prior\":[],\"seen\":[],"
          + "\"byType\":{\"HOME\":{\"street\":\"4 Elm\",\"city\":\"Vail\",\"geo\":{\"lat\":7.0,\"lng\":8.0}}}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void recursionRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> treeJson = loader.loadClass("demo.internal.TreeJSON");
      Class<?> tree = loader.loadClass("demo.Tree");
      String json = "{\"name\":\"root\",\"kids\":[{\"name\":\"a\",\"kids\":[]},"
          + "{\"name\":\"b\",\"kids\":[{\"name\":\"b1\",\"kids\":[]}]}]}";
      Object o = treeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(treeJson.getMethod("toJSON", tree).invoke(null, o), json);
    }
  }

  @Test
  public void nullNestedFieldOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"prior\":[],\"seen\":[],\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void nullNestedFieldEmittedWhenOmitNullsFalse() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> looseJson = loader.loadClass("demo.internal.LooseJSON");
      Class<?> loose = loader.loadClass("demo.Loose");
      String json = "{\"name\":\"Bob\",\"address\":null}";
      Object o = looseJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(looseJson.getMethod("toJSON", loose).invoke(null, o), json);
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=NestedCodegenTest`
Expected: FAIL — `@BeforeClass` compile fails; diagnostics include "has unsupported type [demo.Address]" (nested currently rejected by `isSupportedComponentType`).

- [ ] **Step 4: Add the nested predicates to `TypeView`**

In `src/main/java/org/lattejava/json/jte/TypeView.java`, add the import (after the existing `import javax.lang.model.type.TypeKind;`, alphabetized into its own line):

```java
import javax.lang.model.type.TypeKind;

import org.lattejava.json.JSON;
```

Add these instance methods (alphabetical among the `is*`/accessor methods — place `decl()` before `element()`, `isNested()` after `isMap()`, `isRecord()` after `isPrimitive()`, and `nestedCompanion()` after `name()`):

```java
  /**
   * The reference form to write for this type in generated source: the fully-qualified name for a nested {@code @JSON}
   * record (so no import is needed and same-simple-name collisions cannot occur), else the simple name.
   */
  public String decl() {
    return isNested() ? name() : simpleName();
  }
```

```java
  /**
   * Whether this type is a record annotated with {@code @JSON} — a nested type the processor can recurse into. Note:
   * {@code @JSON} is {@code SOURCE}-retained, so a record from a compiled dependency reports {@code false} here and is
   * rejected as un-annotated, which is also the cross-module restriction.
   */
  public boolean isNested() {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element element = ((javax.lang.model.type.DeclaredType) type).asElement();
    return element.getKind() == ElementKind.RECORD && element.getAnnotation(JSON.class) != null;
  }
```

```java
  /**
   * Whether this type is a record (annotated or not) — used to give a precise "not @JSON-annotated" diagnostic.
   */
  public boolean isRecord() {
    return type.getKind() == TypeKind.DECLARED
        && ((javax.lang.model.type.DeclaredType) type).asElement().getKind() == ElementKind.RECORD;
  }
```

```java
  /**
   * The fully-qualified name of the generated companion for this nested type, e.g. {@code demo.internal.AddressJSON}.
   * Only meaningful when {@link #isNested()} is true.
   */
  public String nestedCompanion() {
    Element element = ((javax.lang.model.type.DeclaredType) type).asElement();
    String pkg = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
    return (pkg.isEmpty() ? "internal" : pkg + ".internal") + "." + simpleName() + "JSON";
  }
```

- [ ] **Step 5: Render nested types fully-qualified in `declType.jte`**

Replace the body of `src/main/jte/declType.jte` (keep line 1 `@param`):

```jte
@param org.lattejava.json.jte.TypeView type
${type.isMap() ? "Map<" + type.key().decl() + ", " + type.value().decl() + ">" : type.isCollection() ? type.kind() + "<" + type.element().decl() + ">" : type.decl()}
```

(For non-nested types `decl()` equals `simpleName()`, so existing output is unchanged.)

- [ ] **Step 6: Nested object-member serialization in `memberCall.jte`**

Replace `src/main/jte/memberCall.jte` (prepend the nested branch):

```jte
@param org.lattejava.json.jte.TypeView type
@param String key
@param String val
${type.isNested() ? "object(" + key + ", " + val + " == null ? null : " + type.nestedCompanion() + ".toJSON(" + val + "))" : type.isEnum() ? "string(" + key + ", " + val + " == null ? null : " + val + ".name())" : switch (type.name()) {
  case "java.lang.String" -> "string(" + key + ", " + val + ")";
  case "boolean", "java.lang.Boolean" -> "bool(" + key + ", " + val + ")";
  case "byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> "integer(" + key + ", " + val + ")";
  case "float", "double" -> "decimal(" + key + ", java.math.BigDecimal.valueOf(" + val + "))";
  case "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "decimal(" + key + ", " + val + ")";
  case "java.math.BigInteger" -> "bigInteger(" + key + ", " + val + ")";
  default -> "string(" + key + ", " + val + " == null ? null : " + val + ".toString())";
}}
```

- [ ] **Step 7: Nested element serialization in `arrayAppend.jte`**

Replace `src/main/jte/arrayAppend.jte` (prepend the nested branch; `raw(null)` emits `null`, preserving array length):

```jte
@param org.lattejava.json.jte.TypeView type
@param String expr
${type.isNested() ? ".raw(" + expr + " == null ? null : " + type.nestedCompanion() + ".toJSON(" + expr + "))" : type.isEnum() ? ".string(" + expr + " == null ? null : " + expr + ".name())" : switch (type.name()) {
  case "java.lang.String" -> ".string(" + expr + ")";
  case "boolean", "java.lang.Boolean" -> ".bool(" + expr + ")";
  case "byte", "short", "int", "long" -> ".integer(" + expr + ")";
  case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> ".integer(" + expr + " == null ? null : " + expr + ".longValue())";
  case "float", "double" -> ".decimal(java.math.BigDecimal.valueOf(" + expr + "))";
  case "java.lang.Float", "java.lang.Double" -> ".decimal(" + expr + " == null ? null : java.math.BigDecimal.valueOf(" + expr + "))";
  case "java.math.BigInteger" -> ".bigInteger(" + expr + ")";
  case "java.math.BigDecimal" -> ".decimal(" + expr + ")";
  default -> ".string(" + expr + " == null ? null : " + expr + ".toString())";
}}
```

- [ ] **Step 8: Nested direct-field dispatch in `observerBody.jte`**

In `src/main/jte/observerBody.jte`, replace the `beginObject` method (lines 66-75) with (adds the `@elseif(c.type().isNested())` arm):

```jte
  @Override public JSONObjectHandler beginObject(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isMap())
      case "${c.name()}" -> { return new @template.cap(name = c.name())MapObserver(); }
@elseif(c.type().isNested())
      case "${c.name()}" -> { return new ${c.type().nestedCompanion()}(); }
@endif
@endfor
    }
    throw new IllegalStateException("nested objects unsupported in this release");
  }
```

In the same file, replace the `object` method (lines 76-86) with (the assignment now also covers nested fields; `declType` renders the nested cast fully-qualified):

```jte
  @SuppressWarnings("unchecked")
  @Override public void object(String key, Object value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isMap() || c.type().isNested())
      case "${c.name()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
```

- [ ] **Step 9: Nested element dispatch in `arrayObserver.jte`**

Replace the entire body of `src/main/jte/arrayObserver.jte` with (adds an `@if(elem.isNested())` scalar-stub branch and conditional `beginObject`/`object`):

```jte
@import org.lattejava.json.jte.Component
@param Component c
!{var elem = c.type().element();}
!{var impl = c.type().isSet() ? "java.util.LinkedHashSet" : "java.util.ArrayList";}
!{var stub = "throw new JSONProcessingException(\"unexpected JSON value for element type [" + elem.name() + "]\");";}
  private static String ${c.name()}ToJSON(@template.declType(type = c.type()) v) {
    var b = new JSONArrayBuilder();
    for (var e : v) b@template.arrayAppend(type = elem, expr = "e");
    return b.build();
  }
  private static final class @template.cap(name = c.name())ArrayObserver implements JSONArrayObserver<@template.declType(type = c.type())> {
    private final @template.declType(type = c.type()) acc = new ${impl}<>();
@if(elem.isNested())
    @Override public void string(String value) { ${stub} }
    @Override public void integer(long value) { ${stub} }
    @Override public void bigInteger(java.math.BigInteger value) { ${stub} }
    @Override public void decimal(java.math.BigDecimal value) { ${stub} }
    @Override public void bool(boolean value) { ${stub} }
@elseif(elem.isStringForm())
    @Override public void string(String value) { acc.add(@template.fromString(type = elem, expr = "value")); }
    @Override public void integer(long value) { ${stub} }
    @Override public void bigInteger(java.math.BigInteger value) { ${stub} }
    @Override public void decimal(java.math.BigDecimal value) { ${stub} }
    @Override public void bool(boolean value) { ${stub} }
@elseif(elem.isBool())
    @Override public void string(String value) { ${stub} }
    @Override public void integer(long value) { ${stub} }
    @Override public void bigInteger(java.math.BigInteger value) { ${stub} }
    @Override public void decimal(java.math.BigDecimal value) { ${stub} }
    @Override public void bool(boolean value) { acc.add(value); }
@else
    @Override public void string(String value) { ${stub} }
    @Override public void integer(long value) { acc.add(@template.narrow(type = elem, source = "integer")); }
    @Override public void bigInteger(java.math.BigInteger value) { acc.add(@template.narrow(type = elem, source = "bigInteger")); }
    @Override public void decimal(java.math.BigDecimal value) { acc.add(@template.narrow(type = elem, source = "decimal")); }
    @Override public void bool(boolean value) { ${stub} }
@endif
    @Override public void nullValue() { acc.add(null); }
    @Override public @template.declType(type = c.type()) finish() { return acc; }
@if(elem.isNested())
    @Override public JSONObjectHandler beginObject() { return new ${elem.nestedCompanion()}(); }
    @Override public JSONArrayObserver<?> beginArray() { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(Object value) { acc.add((${elem.decl()}) value); }
    @Override public void array(Object value) {}
@else
    @Override public JSONObjectHandler beginObject() { throw new JSONProcessingException("nested objects in collections unsupported"); }
    @Override public JSONArrayObserver<?> beginArray() { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(Object value) {}
    @Override public void array(Object value) {}
@endif
  }
```

- [ ] **Step 10: Nested value dispatch in `mapObserver.jte`**

Replace the entire body of `src/main/jte/mapObserver.jte` with (adds an `@if(valType.isNested())` scalar-stub branch and conditional `beginObject`/`object`):

```jte
@import org.lattejava.json.jte.Component
@param Component c
@param boolean omitNulls
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
@if(valType.isNested())
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
@if(valType.isNested())
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
```

- [ ] **Step 11: Accept nested types in `JSONProcessor.isSupportedComponentType`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, change the final return of `isSupportedComponentType` (line 175) from:

```java
    return type.isPrimitive() || type.isNumeric() || type.isBool() || type.isStringForm();
```

to:

```java
    return type.isPrimitive() || type.isNumeric() || type.isBool() || type.isStringForm() || type.isNested();
```

- [ ] **Step 12: Run the nested test to verify it passes**

Run: `latte test --test=NestedCodegenTest`
Expected: PASS — all 7 tests green.

- [ ] **Step 13: Run the full suite — expect the obsolete rejection test to fail**

Run: `latte test`
Expected: one failure — `CollectionRejectionTest.collectionOfJSONElementRejected` (a `List<@JSON>` element is now valid, so `JsonElement` compiles and the expected error is no longer emitted). Everything else green.

- [ ] **Step 14: Remove the now-invalid rejection fixture component and test**

Delete `src/test/resources/fixtures/badcollections/demo/JsonElement.java` and `src/test/resources/fixtures/badcollections/demo/Inner.java`:

```bash
git rm src/test/resources/fixtures/badcollections/demo/JsonElement.java \
       src/test/resources/fixtures/badcollections/demo/Inner.java
```

In `src/test/java/org/lattejava/json/tests/processor/CollectionRejectionTest.java`, delete the entire `collectionOfJSONElementRejected` method (lines 31-38, including its `@Test`).

- [ ] **Step 15: Run the full suite**

Run: `latte test`
Expected: PASS — full suite green (was 173; now 173 − 1 removed + 7 added = 179).

- [ ] **Step 16: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/TypeView.java \
        src/main/jte/declType.jte src/main/jte/memberCall.jte src/main/jte/arrayAppend.jte \
        src/main/jte/observerBody.jte src/main/jte/arrayObserver.jte src/main/jte/mapObserver.jte \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/nested \
        src/test/java/org/lattejava/json/tests/processor/NestedCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/CollectionRejectionTest.java
git commit -m "feat: nested @JSON objects in fields, collections, and map values

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Reject a nested type that is not `@JSON`-annotated

A record component referencing a record without `@JSON` (directly, or as a `List`/`Set`/`Map` element/value) must fail compilation with a precise message. This also covers cross-module references (a dependency's `@JSON` is `SOURCE`-retained and invisible, so it reads as un-annotated).

**Files:**
- Create: `src/test/resources/fixtures/badnested/module-info.java`, `.../demo/Plain.java`, `.../demo/HasPlain.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/NestedRejectionTest.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (`validateComponents` messages + a `notJSON` helper)

- [ ] **Step 1: Write the rejection fixture**

`src/test/resources/fixtures/badnested/module-info.java`:

```java
module demo.badnested {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/badnested/demo/Plain.java` (a record WITHOUT `@JSON`):

```java
package demo;

public record Plain(String x) {
}
```

`src/test/resources/fixtures/badnested/demo/HasPlain.java` (direct field + list element both reference the un-annotated record):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record HasPlain(Plain p, List<Plain> ps) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/NestedRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class NestedRejectionTest {
  @Test
  public void directNonJSONRecordRejected() throws Exception {
    var r = ProcessorHarness.compile("badnested");
    assertFalse(r.success(), "a nested record without @JSON must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("not @JSON-annotated") && d.contains("[p]")),
        "expected a not-@JSON-annotated error for [p], got: " + r.diagnostics());
  }

  @Test
  public void listOfNonJSONRecordRejected() throws Exception {
    var r = ProcessorHarness.compile("badnested");
    assertFalse(r.success());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("not @JSON-annotated") && d.contains("[ps]")),
        "expected a not-@JSON-annotated error for [ps], got: " + r.diagnostics());
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=NestedRejectionTest`
Expected: FAIL — compilation fails (good) but the message is the generic "has unsupported type [demo.Plain]", not "not @JSON-annotated", so the assertions fail.

- [ ] **Step 4: Add the precise message to `validateComponents`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, add this helper method (alphabetical among the private methods, after `isSupportedComponentType`):

```java
  private String notJSON(RecordComponentElement c, TypeView t) {
    return "@JSON component [" + c.getSimpleName() + "] references record type [" + t.name()
        + "] which is not @JSON-annotated; add @JSON to it or remove the component";
  }
```

In the **Map value** unsupported branch (currently lines 215-220), replace:

```java
          if (!isSupportedComponentType(v)) {
            error(c, "@JSON component [" + c.getSimpleName() + "] has an unsupported Map value type ["
                + v.name() + "]");
            ok = false;
            continue;
          }
```

with:

```java
          if (!isSupportedComponentType(v)) {
            error(c, v.isRecord() && !v.isNested() ? notJSON(c, v)
                : "@JSON component [" + c.getSimpleName() + "] has an unsupported Map value type ["
                  + v.name() + "]");
            ok = false;
            continue;
          }
```

In the **collection element** unsupported branch (currently lines 233-238), replace:

```java
        if (!isSupportedComponentType(e)) {
          error(c, "@JSON component [" + c.getSimpleName() + "] has an unsupported "
              + type.kind() + " element type [" + e.name() + "]");
          ok = false;
          continue;
        }
```

with:

```java
        if (!isSupportedComponentType(e)) {
          error(c, e.isRecord() && !e.isNested() ? notJSON(c, e)
              : "@JSON component [" + c.getSimpleName() + "] has an unsupported "
                + type.kind() + " element type [" + e.name() + "]");
          ok = false;
          continue;
        }
```

In the **direct** unsupported branch (currently lines 243-248), replace:

```java
      if (!isSupportedComponentType(type)) {
        error(c, "@JSON component [" + c.getSimpleName() + "] has unsupported type ["
            + type.name() + "] (supported: primitives, boxed primitives, String, "
            + "BigInteger, BigDecimal, enums, UUID, and java.time types)");
        ok = false;
      }
```

with:

```java
      if (!isSupportedComponentType(type)) {
        error(c, type.isRecord() && !type.isNested() ? notJSON(c, type)
            : "@JSON component [" + c.getSimpleName() + "] has unsupported type ["
              + type.name() + "] (supported: primitives, boxed primitives, String, "
              + "BigInteger, BigDecimal, enums, UUID, java.time types, and @JSON records)");
        ok = false;
      }
```

(The non-record path keeps the original "unsupported type … [tags]" wording, so `ProcessorErrorsTest.unsupportedComponentTypeIsRejected` — `java.io.File` — stays green. Only the supported-list text gains "and @JSON records".)

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=NestedRejectionTest`
Expected: PASS — both assertions green (compilation fails with "not @JSON-annotated" for `[p]` and `[ps]`).

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS — full suite green (181 tests: 179 + 2). `ProcessorErrorsTest` and `CollectionRejectionTest` unchanged-green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/badnested \
        src/test/java/org/lattejava/json/tests/processor/NestedRejectionTest.java
git commit -m "feat: reject nested record components that are not @JSON-annotated

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Remove the obsolete orphan fixture and final verification

**Files:**
- Delete: `src/test/resources/fixtures/kitchensinknested/`

- [ ] **Step 1: Confirm the fixture is unreferenced, then delete it**

Run: `grep -rn "kitchensinknested" src/test/java`
Expected: no output (no test compiles it).

```bash
git rm -r src/test/resources/fixtures/kitchensinknested
```

- [ ] **Step 2: Full suite**

Run: `latte test`
Expected: PASS — entire suite green (181 tests). Confirm `HelperEmissionTest`, `MapCodegenTest`, `ListCodegenTest`, `SetCodegenTest`, `EnumCollectionCodegenTest`, `CollectionRejectionTest`, `ProcessorErrorsTest` all green.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: remove orphan kitchensinknested fixture (nested records now supported)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §1 rules (nested in field/list/set/map-value; nested type must be `@JSON`; recursion) → Task 1 (fixture covers all positions + `Tree` recursion + cross-package `Geo`).
- §2 `TypeView.isNested()` + nested-name accessors → Task 1 Step 4 (`isNested`, `isRecord`, `decl`, `nestedCompanion`).
- §3 validation accepts nested; rejects non-`@JSON` → Task 1 Step 11 (accept) + Task 2 Step 4 (reject with precise message).
- §4 serialization via raw embed (`JSONBuilder.object`, `JSONArrayBuilder.raw`) → Task 1 Steps 6-7, 9-10.
- §5 deserialization via child observer dispatch → Task 1 Steps 8-10.
- §6 references legal across packages → fully-qualified (Notes); `Geo` in `demo.geo` exercises it.
- §Testing (field/list/set/map/deep/recursion/omit-nulls/reject-not-@JSON) → Task 1 + Task 2. Cross-module rejection → folded into "not @JSON-annotated" (Notes), tested in Task 2.
- §Risks (import collision) → eliminated by fully-qualified references; (cast safety) → every round-trip test exercises the `(Type) value` casts.

**Placeholder scan:** none. Every template is given as a complete file; every `JSONProcessor` edit is an exact before/after; every test is full code.

**Type consistency:** `TypeView.isNested()/isRecord()/decl()/nestedCompanion()` are defined in Task 1 Step 4 and used consistently in Steps 5-10 (`decl()` in `declType.jte`/casts, `nestedCompanion()` in serialize + `beginObject`) and in Task 2 Step 4 (`isRecord()`/`isNested()`). `notJSON(RecordComponentElement, TypeView)` is defined and used within Task 2 Step 4. Generated companion names follow the existing `<Simple>JSON` / `<package>.internal` convention used everywhere else.
