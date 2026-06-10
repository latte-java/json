# Polymorphism Codegen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen for OpenAPI-style polymorphic sealed `@JSON` interfaces (`@JSONTypeInfo`) with `@JSON` record subtypes (`@JSONSubtype`): a polymorphic dispatcher companion, discriminator-first subtype serialization, and use of a polymorphic type as root / field / `List`/`Set` element / `Map` value / nested.

**Architecture:** Pure codegen on the already-built polymorphism runtime (`JSONPolymorphicObserver`, the parser's scan-ahead dispatch at every `beginObject` site) and the nested-object machinery shipped last cycle. A new `polymorphic.jte` + `PolymorphicView` render the dispatcher companion; record subtypes gain a discriminator-first builder line and a discriminator-ignoring `string()` arm; polymorphic-type *usage* reuses the nested codepath by generalizing the `isNested()` predicate to `hasCompanion()`. Nested types/companions are referenced fully-qualified (consistent with the nested feature). No runtime change, no `module-info` change.

**Tech Stack:** Java 25, JTE 3.2.1 templates (`src/main/jte/*.jte`), `javax.annotation.processing`, Latte build (`latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-06-polymorphism-codegen-design.md`

---

## Notes (read first)

- **Fully-qualified references.** Subtypes and companions are referenced by FQN (`demo.Dog`, `demo.internal.DogJSON`) — no imports, no same-simple-name collisions, consistent with the nested feature.
- **`hasCompanion()` generalization.** `TypeView.hasCompanion()` = `isNested() || isPolymorphic()`. The five dispatch templates and `decl()`/`isSupportedComponentType` switch their `isNested()` checks to `hasCompanion()`. For records `isPolymorphic()` is false, so nested behavior is unchanged (the 182 existing tests stay green).
- **The parser skips the discriminator during polymorphic dispatch**, so subtype observers reached via a `PetJSON` never see the discriminator key. The discriminator-ignoring `string()` arm (Task 1) exists for *direct* `DogJSON.fromJSON(...)` and `@JSON(strict=true)` subtypes.
- **`JTEEngine.render` re-indents output by brace depth**, so template indentation is cosmetic — author for readability.
- **Reindent note for `polymorphic.jte`:** it has no `@JSON` record fields; like the other templates, `@for`/`@if` lines are flattened then re-indented by brace depth. Author the switch arms one-per-line.

---

## File Structure

**Create:**
- `src/main/jte/polymorphic.jte` — the dispatcher companion template.
- `src/main/java/org/lattejava/json/jte/PolymorphicView.java` — model for `polymorphic.jte` (+ nested `Subtype` record).
- `src/test/resources/fixtures/poly/` — fixture (module `demo.poly`): `Pet`, `Dog`, `Cat`, `Bird`, plus Task-2 usage types; `module-info.java`.
- `src/test/java/org/lattejava/json/tests/processor/PolyCodegenTest.java` (Task 1), `PolyUsageTest.java` (Task 2), `PolyRejectionTest.java` (Task 3).
- `src/test/resources/fixtures/badpoly_*/` — rejection fixtures (Task 3).

**Modify:**
- `src/main/java/org/lattejava/json/JSONProcessor.java` — relax the records-only guard; branch to `generatePolymorphic`; subtype discriminator computation; `validatePolymorphic` (Task 3); `isSupportedComponentType` (Task 2).
- `src/main/java/org/lattejava/json/jte/TypeView.java` — `isPolymorphic()`, `hasCompanion()`; `decl()` uses `hasCompanion()` (Task 2).
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — `discriminatorKey`/`discriminatorValue`.
- `src/main/jte/companion.jte` — discriminator-first builder line.
- `src/main/jte/observerBody.jte` — discriminator-ignoring `string()` arm.
- `src/main/jte/memberCall.jte`, `arrayAppend.jte`, `arrayObserver.jte`, `mapObserver.jte` — `isNested()` → `hasCompanion()` (Task 2).

**Acceptance gate every task:** full suite green — `latte test` (currently 182).

---

## Task 1: Polymorphic hierarchy codegen

Generate the `PetJSON` dispatcher, make subtypes emit the discriminator first, and make subtype observers ignore their own discriminator key.

**Files:**
- Create: `src/test/resources/fixtures/poly/module-info.java`, `.../demo/Pet.java`, `.../demo/Dog.java`, `.../demo/Cat.java`, `.../demo/Bird.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/PolyCodegenTest.java`
- Create: `src/main/java/org/lattejava/json/jte/PolymorphicView.java`
- Create: `src/main/jte/polymorphic.jte`
- Modify: `src/main/java/org/lattejava/json/jte/CompanionView.java`
- Modify: `src/main/jte/companion.jte`, `src/main/jte/observerBody.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/poly/module-info.java`:

```java
module demo.poly {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/poly/demo/Pet.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "petType")
public sealed interface Pet permits Dog, Cat, Bird {
}
```

`src/test/resources/fixtures/poly/demo/Dog.java` (no `@JSONSubtype` → default value is the simple name "Dog"):

```java
package demo;

import module org.lattejava.json;

@JSON
public record Dog(String name, int packSize) implements Pet {
}
```

`src/test/resources/fixtures/poly/demo/Cat.java` (custom discriminator value):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("kitty")
public record Cat(String name, int lives) implements Pet {
}
```

`src/test/resources/fixtures/poly/demo/Bird.java` (strict — exercises the discriminator-ignoring arm):

```java
package demo;

import module org.lattejava.json;

@JSON(strict = true)
@JSONSubtype("Bird")
public record Bird(String name) implements Pet {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/PolyCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolyCodegenTest {
  static ProcessorHarness.Result poly;

  @BeforeClass
  public void compileOnce() throws Exception {
    poly = ProcessorHarness.compile("poly");
    assertTrue(poly.success(), poly.diagnostics().toString());
  }

  @Test
  public void rootRoundTripsDogDiscriminatorFirst() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      String json = "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}";
      Object dog = petJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Dog").getMethod("name").invoke(dog), "Rex");
      assertEquals(petJson.getMethod("toJSON", pet).invoke(null, dog), json);
    }
  }

  @Test
  public void customDiscriminatorValueRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      String json = "{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}";
      Object cat = petJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Cat").getMethod("lives").invoke(cat), 9);
      assertEquals(petJson.getMethod("toJSON", pet).invoke(null, cat), json);
    }
  }

  @Test
  public void discriminatorLastOnInputStillDispatches() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Object dog = petJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Rex\",\"packSize\":3,\"petType\":\"Dog\"}");
      assertEquals(loader.loadClass("demo.Dog").getMethod("packSize").invoke(dog), 3);
    }
  }

  @Test
  public void toJSONBytesMatchesToJSON() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      Object dog = petJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}");
      String s = (String) petJson.getMethod("toJSON", pet).invoke(null, dog);
      byte[] b = (byte[]) petJson.getMethod("toJSONBytes", pet).invoke(null, dog);
      assertEquals(new String(b, StandardCharsets.UTF_8), s);
    }
  }

  @Test
  public void unknownDiscriminatorThrows() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      try {
        petJson.getMethod("fromJSON", String.class)
            .invoke(null, "{\"petType\":\"Fish\",\"name\":\"Nemo\"}");
        fail("expected unknown discriminator to throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertTrue(e.getCause().getMessage().contains("Unknown discriminator value [Fish]"),
            "got: " + e.getCause().getMessage());
      }
    }
  }

  @Test
  public void missingDiscriminatorThrows() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      try {
        petJson.getMethod("fromJSON", String.class).invoke(null, "{\"name\":\"Anon\"}");
        fail("expected missing discriminator to throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertNotNull(e.getCause());
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException");
      }
    }
  }

  @Test
  public void strictSubtypeIgnoresDiscriminatorOnDirectParse() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> birdJson = loader.loadClass("demo.internal.BirdJSON");
      Object bird = birdJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"Bird\",\"name\":\"Tweety\"}");
      assertEquals(loader.loadClass("demo.Bird").getMethod("name").invoke(bird), "Tweety");
    }
  }

  @Test
  public void subtypeToJSONEmitsDiscriminatorFirst() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> catJson = loader.loadClass("demo.internal.CatJSON");
      Class<?> cat = loader.loadClass("demo.Cat");
      Object c = catJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}");
      String json = (String) catJson.getMethod("toJSON", cat).invoke(null, c);
      assertTrue(json.startsWith("{\"petType\":\"kitty\""), "got: " + json);
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=PolyCodegenTest`
Expected: FAIL — `@BeforeClass` compile fails: the processor rejects `Pet` with "@JSON supports only records" (interface parent not yet handled).

- [ ] **Step 4: Add `discriminatorKey`/`discriminatorValue` to `CompanionView`**

In `src/main/java/org/lattejava/json/jte/CompanionView.java`, add two instance fields (alphabetical: `discriminatorKey`, `discriminatorValue` go before `enumImports`), two constructor params (appended at the end), and two accessors. The new constructor and accessors:

```java
  private final String companionName;
  private final String companionPackage;
  private final List<Component> components;
  private final String discriminatorKey;
  private final String discriminatorValue;
  private final List<String> enumImports;
  private final String internalPackage;
  private final boolean omitNulls;
  private final String qualifiedType;
  private final String simpleName;
  private final boolean strict;

  public CompanionView(String companionPackage, String internalPackage, String qualifiedType, String simpleName,
                       String companionName, boolean omitNulls, boolean strict, List<String> enumImports,
                       List<Component> components, String discriminatorKey, String discriminatorValue) {
    this.companionPackage = companionPackage;
    this.internalPackage = internalPackage;
    this.qualifiedType = qualifiedType;
    this.simpleName = simpleName;
    this.companionName = companionName;
    this.omitNulls = omitNulls;
    this.strict = strict;
    this.enumImports = enumImports;
    this.components = components;
    this.discriminatorKey = discriminatorKey;
    this.discriminatorValue = discriminatorValue;
  }
```

Add the accessors (alphabetical among the methods, after `components()`):

```java
  public String discriminatorKey() {
    return discriminatorKey;
  }

  public String discriminatorValue() {
    return discriminatorValue;
  }
```

- [ ] **Step 5: Create `PolymorphicView`**

`src/main/java/org/lattejava/json/jte/PolymorphicView.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.base;

/**
 * Top-level template model for one generated polymorphic dispatcher companion ({@code <Type>JSON implements
 * JSONPolymorphicObserver}). Carries the discriminator key plus the ordered permitted subtypes, each with its
 * discriminator value and fully-qualified type/companion names. Built by {@code JSONProcessor.generatePolymorphic};
 * consumed by {@code polymorphic.jte}. Holds no code-string logic.
 *
 * @author Brian Pontarelli
 */
public final class PolymorphicView {
  private final String companionName;
  private final String companionPackage;
  private final String discriminatorKey;
  private final String internalPackage;
  private final String qualifiedType;
  private final String simpleName;
  private final List<Subtype> subtypes;

  public PolymorphicView(String companionPackage, String internalPackage, String qualifiedType, String simpleName,
                         String companionName, String discriminatorKey, List<Subtype> subtypes) {
    this.companionPackage = companionPackage;
    this.internalPackage = internalPackage;
    this.qualifiedType = qualifiedType;
    this.simpleName = simpleName;
    this.companionName = companionName;
    this.discriminatorKey = discriminatorKey;
    this.subtypes = subtypes;
  }

  public String companionName() {
    return companionName;
  }

  public String companionPackage() {
    return companionPackage;
  }

  public String discriminatorKey() {
    return discriminatorKey;
  }

  public String internalPackage() {
    return internalPackage;
  }

  public String qualifiedType() {
    return qualifiedType;
  }

  public String simpleName() {
    return simpleName;
  }

  public List<Subtype> subtypes() {
    return subtypes;
  }

  /** One permitted subtype: its discriminator value plus fully-qualified type and companion names. */
  public record Subtype(String value, String typeFqn, String companionFqn) {
  }
}
```

- [ ] **Step 6: Create `polymorphic.jte`**

`src/main/jte/polymorphic.jte`:

```jte
@import org.lattejava.json.jte.PolymorphicView
@param PolymorphicView view
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package ${view.companionPackage()};

import module java.base;
import ${view.qualifiedType()};
import ${view.internalPackage()}.JSONObserver;
import ${view.internalPackage()}.JSONParser;
import ${view.internalPackage()}.JSONPolymorphicObserver;
import ${view.internalPackage()}.JSONProcessingException;

/**
 * Generated by org.lattejava.json.JSONProcessor. Do not edit.
 *
 * @author Latte JSON
 */
public final class ${view.companionName()} implements JSONPolymorphicObserver<${view.simpleName()}> {
  @Override public String discriminatorKey() {
    return "${view.discriminatorKey()}";
  }
  @Override public JSONObserver<? extends ${view.simpleName()}> observerFor(String value) {
    switch (value) {
@for(var s : view.subtypes())
      case "${s.value()}" -> { return new ${s.companionFqn()}(); }
@endfor
    }
    throw new JSONProcessingException("Unknown discriminator value [" + value + "] for [${view.discriminatorKey()}]");
  }

  public static ${view.simpleName()} fromJSON(String json) {
    return new JSONParser().parsePolymorphic(json, new ${view.companionName()}());
  }

  public static ${view.simpleName()} fromJSON(byte[] json) {
    return new JSONParser().parsePolymorphic(json, new ${view.companionName()}());
  }

  public static String toJSON(${view.simpleName()} value) {
    return switch (value) {
@for(var s : view.subtypes())
      case ${s.typeFqn()} v -> ${s.companionFqn()}.toJSON(v);
@endfor
    };
  }

  public static byte[] toJSONBytes(${view.simpleName()} value) {
    return switch (value) {
@for(var s : view.subtypes())
      case ${s.typeFqn()} v -> ${s.companionFqn()}.toJSONBytes(v);
@endfor
    };
  }
}
```

Note: `observerFor` uses a statement `switch` + arrow-block + trailing throw (not a switch expression) to avoid needing a `default` that returns — the trailing `throw` covers the unmatched case and keeps the method returning a value on every path. `toJSON`/`toJSONBytes` use exhaustive switch *expressions* over the sealed type (no `default` needed).

- [ ] **Step 7: Emit the discriminator first in `companion.jte`**

In `src/main/jte/companion.jte`, change the `builder(...)` method so the discriminator line is emitted first when present. Replace:

```jte
  private static JSONBuilder builder(${view.simpleName()} value) {
    return new JSONBuilder(${view.omitNulls()})
@for(Component c : view.components())
```

with:

```jte
  private static JSONBuilder builder(${view.simpleName()} value) {
    return new JSONBuilder(${view.omitNulls()})
@if(!view.discriminatorKey().isEmpty())
        .string("${view.discriminatorKey()}", "${view.discriminatorValue()}")
@endif
@for(Component c : view.components())
```

- [ ] **Step 8: Ignore the discriminator key in `observerBody.jte`**

In `src/main/jte/observerBody.jte`, add a discriminator-ignoring arm at the top of the `string(...)` switch. Replace:

```jte
  @Override public void string(String key, String value) {
    switch (key) {
@for(Component c : view.components())
```

with:

```jte
  @Override public void string(String key, String value) {
    switch (key) {
@if(!view.discriminatorKey().isEmpty())
      case "${view.discriminatorKey()}" -> { /* discriminator: ignore */ }
@endif
@for(Component c : view.components())
```

- [ ] **Step 9: Wire the processor — relax the guard, branch to `generatePolymorphic`, compute subtype discriminator**

In `src/main/java/org/lattejava/json/JSONProcessor.java`:

**(a)** Replace the `process()` loop body (lines 47-70) with:

```java
    for (Element e : annotated) {
      TypeElement type = (TypeElement) e;
      boolean polyParent = e.getKind() == ElementKind.INTERFACE && type.getAnnotation(JSONTypeInfo.class) != null;
      if (e.getKind() != ElementKind.RECORD && !polyParent) {
        error(e, "@JSON supports only records and sealed @JSONTypeInfo interfaces in this release; ["
            + qualified(e) + "] is a [" + e.getKind() + "]");
        continue;
      }

      ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
      if (module == null || module.isUnnamed()) {
        error(e, "@JSON requires a named module (module-info.java); type [" + type.getQualifiedName() + "] is in the unnamed module");
        continue;
      }

      if (polyParent) {
        if (!helpersEmitted) {
          emitHelpers(module);
          helpersEmitted = true;
        }
        generatePolymorphic(type, module);
        continue;
      }

      if (!validateComponents(type)) {
        continue;
      }

      if (!helpersEmitted) {
        emitHelpers(module);
        helpersEmitted = true;
      }

      generateCompanion(type, module);
    }
```

**(b)** In `generateCompanion`, compute the subtype discriminator before building the `CompanionView`. Replace the `CompanionView view = ...` construction (lines 118-119) with:

```java
    String discriminatorKey = "";
    String discriminatorValue = "";
    for (TypeMirror itf : record.getInterfaces()) {
      TypeElement itfEl = (TypeElement) ((javax.lang.model.type.DeclaredType) itf).asElement();
      JSONTypeInfo ti = itfEl.getAnnotation(JSONTypeInfo.class);
      if (ti != null) {
        discriminatorKey = ti.property();
        discriminatorValue = discriminatorValueOf(record);
        break;
      }
    }

    CompanionView view = new CompanionView(companionPkg, internalPkg, qualifiedType, simpleName, companion,
        readOmitNulls(record), readStrict(record), List.copyOf(enumImports), components,
        discriminatorKey, discriminatorValue);
```

**(c)** Add `generatePolymorphic` and `discriminatorValueOf` (place `generatePolymorphic` after `generateCompanion`; `discriminatorValueOf` alphabetically among private methods):

```java
  void generatePolymorphic(TypeElement iface, ModuleElement module) {
    String internalPkg = module.getQualifiedName() + ".internal";
    String typePkg = processingEnv.getElementUtils().getPackageOf(iface).getQualifiedName().toString();
    String companionPkg = typePkg.isEmpty() ? "internal" : typePkg + ".internal";
    String simpleName = iface.getSimpleName().toString();
    String companion = simpleName + "JSON";
    String qualifiedType = iface.getQualifiedName().toString();
    String discriminatorKey = iface.getAnnotation(JSONTypeInfo.class).property();

    List<PolymorphicView.Subtype> subtypes = new ArrayList<>();
    for (TypeMirror permitted : iface.getPermittedSubclasses()) {
      TypeElement sub = (TypeElement) ((javax.lang.model.type.DeclaredType) permitted).asElement();
      String subPkg = processingEnv.getElementUtils().getPackageOf(sub).getQualifiedName().toString();
      String subCompanionPkg = subPkg.isEmpty() ? "internal" : subPkg + ".internal";
      subtypes.add(new PolymorphicView.Subtype(
          discriminatorValueOf(sub),
          sub.getQualifiedName().toString(),
          subCompanionPkg + "." + sub.getSimpleName() + "JSON"));
    }

    PolymorphicView view = new PolymorphicView(companionPkg, internalPkg, qualifiedType, simpleName,
        companion, discriminatorKey, subtypes);
    String source = JTEEngine.render("polymorphic.jte", Map.of("view", view));

    try {
      var file = processingEnv.getFiler().createSourceFile(companionPkg + "." + companion, iface);
      try (Writer w = file.openWriter()) {
        w.write(source);
      }
    } catch (IOException ioe) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed writing companion [" + companionPkg + "." + companion + "]: " + ioe.getMessage(),
          iface);
    }
  }

  private String discriminatorValueOf(TypeElement subtype) {
    JSONSubtype ann = subtype.getAnnotation(JSONSubtype.class);
    String v = ann == null ? "" : ann.value();
    return v.isEmpty() ? subtype.getSimpleName().toString() : v;
  }
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `latte test --test=PolyCodegenTest`
Expected: PASS — all 8 tests green.

- [ ] **Step 11: Run the full suite**

Run: `latte test`
Expected: PASS — 190 tests (182 + 8), 0 failures. The discriminator-first/ignore changes are guarded by `!view.discriminatorKey().isEmpty()`, so non-polymorphic records are unchanged.

- [ ] **Step 12: Commit**

```bash
git add src/main/jte/polymorphic.jte src/main/jte/companion.jte src/main/jte/observerBody.jte \
        src/main/java/org/lattejava/json/jte/PolymorphicView.java \
        src/main/java/org/lattejava/json/jte/CompanionView.java \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/poly \
        src/test/java/org/lattejava/json/tests/processor/PolyCodegenTest.java
git commit -m "feat: polymorphic @JSON hierarchy codegen (dispatcher + discriminator)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Polymorphic-type usage (field, list, map, nested)

Make a polymorphic type usable as a component, reusing the nested codepath via `hasCompanion()`.

**Files:**
- Create: `.../fixtures/poly/demo/Owner.java`, `.../demo/Kennel.java`, `.../demo/Registry.java`, `.../demo/Household.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/PolyUsageTest.java`
- Modify: `src/main/java/org/lattejava/json/jte/TypeView.java`
- Modify: `src/main/jte/memberCall.jte`, `arrayAppend.jte`, `observerBody.jte`, `arrayObserver.jte`, `mapObserver.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (`isSupportedComponentType`)

- [ ] **Step 1: Add usage types to the `poly` fixture**

`src/test/resources/fixtures/poly/demo/Owner.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Owner(String name, Pet pet) {
}
```

`src/test/resources/fixtures/poly/demo/Kennel.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Kennel(String name, List<Pet> pets) {
}
```

`src/test/resources/fixtures/poly/demo/Registry.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Registry(Map<String, Pet> byId) {
}
```

`src/test/resources/fixtures/poly/demo/Household.java` (nested: a record holding a record that holds a polymorphic field):

```java
package demo;

import module org.lattejava.json;

@JSON
public record Household(Owner owner) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/PolyUsageTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolyUsageTest {
  static ProcessorHarness.Result poly;

  @BeforeClass
  public void compileOnce() throws Exception {
    poly = ProcessorHarness.compile("poly");
    assertTrue(poly.success(), poly.diagnostics().toString());
  }

  @Test
  public void polymorphicFieldRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> ownerJson = loader.loadClass("demo.internal.OwnerJSON");
      Class<?> owner = loader.loadClass("demo.Owner");
      String json = "{\"name\":\"Sam\",\"pet\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}}";
      Object o = ownerJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(ownerJson.getMethod("toJSON", owner).invoke(null, o), json);
    }
  }

  @Test
  public void polymorphicListRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> kennelJson = loader.loadClass("demo.internal.KennelJSON");
      Class<?> kennel = loader.loadClass("demo.Kennel");
      String json = "{\"name\":\"Acme\",\"pets\":["
          + "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3},"
          + "{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}]}";
      Object o = kennelJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(kennelJson.getMethod("toJSON", kennel).invoke(null, o), json);
    }
  }

  @Test
  public void polymorphicMapValueRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> registryJson = loader.loadClass("demo.internal.RegistryJSON");
      Class<?> registry = loader.loadClass("demo.Registry");
      String json = "{\"byId\":{"
          + "\"a\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3},"
          + "\"b\":{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}}}";
      Object o = registryJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(registryJson.getMethod("toJSON", registry).invoke(null, o), json);
    }
  }

  @Test
  public void nestedPolymorphismRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> householdJson = loader.loadClass("demo.internal.HouseholdJSON");
      Class<?> household = loader.loadClass("demo.Household");
      String json = "{\"owner\":{\"name\":\"Sam\","
          + "\"pet\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}}}";
      Object o = householdJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(householdJson.getMethod("toJSON", household).invoke(null, o), json);
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=PolyUsageTest`
Expected: FAIL — `@BeforeClass` compile fails: `Owner`'s `pet` component is rejected ("has unsupported type [demo.Pet]") because `isSupportedComponentType` does not yet accept polymorphic types.

- [ ] **Step 4: Add `isPolymorphic()` and `hasCompanion()` to `TypeView`**

In `src/main/java/org/lattejava/json/jte/TypeView.java`, add the import (alphabetical with the existing `import org.lattejava.json.JSON;`):

```java
import org.lattejava.json.JSON;
import org.lattejava.json.JSONTypeInfo;
```

Add `hasCompanion()` and `isPolymorphic()` (alphabetical: `hasCompanion` before `isBool`; `isPolymorphic` after `isNumeric`):

```java
  /**
   * Whether this type has a generated {@code <X>JSON} companion to dispatch to — a nested {@code @JSON} record
   * (an object companion) or a polymorphic {@code @JSON} sealed interface (a {@code JSONPolymorphicObserver}). Both
   * are serialized via {@code <X>JSON.toJSON} and deserialized by returning {@code new <X>JSON()} from a
   * {@code beginObject}.
   */
  public boolean hasCompanion() {
    return isNested() || isPolymorphic();
  }
```

```java
  /**
   * Whether this type is a polymorphic {@code @JSON} hierarchy: an interface carrying both {@code @JSON} and
   * {@code @JSONTypeInfo}. Its companion is a {@code JSONPolymorphicObserver}.
   */
  public boolean isPolymorphic() {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element element = ((javax.lang.model.type.DeclaredType) type).asElement();
    return element.getKind() == ElementKind.INTERFACE
        && element.getAnnotation(JSON.class) != null
        && element.getAnnotation(JSONTypeInfo.class) != null;
  }
```

Change `decl()` to use `hasCompanion()`:

```java
  public String decl() {
    return hasCompanion() ? name() : simpleName();
  }
```

- [ ] **Step 5: Switch the dispatch templates from `isNested()` to `hasCompanion()`**

In each of these templates, replace `isNested()` with `hasCompanion()` (these are the only `isNested()` usages):

- `src/main/jte/memberCall.jte` — `if (type.isNested())` → `if (type.hasCompanion())`
- `src/main/jte/arrayAppend.jte` — `if (type.isNested())` → `if (type.hasCompanion())`
- `src/main/jte/observerBody.jte` — `@elseif(c.type().isNested())` (in `beginObject`) → `@elseif(c.type().hasCompanion())`; and `@if(c.type().isMap() || c.type().isNested())` (in `object`) → `@if(c.type().isMap() || c.type().hasCompanion())`
- `src/main/jte/arrayObserver.jte` — both `@if(elem.isNested())` → `@if(elem.hasCompanion())` (the scalar-stub branch and the `beginObject`/`object` branch)
- `src/main/jte/mapObserver.jte` — both `@if(valType.isNested())` → `@if(valType.hasCompanion())`

- [ ] **Step 6: Accept polymorphic types in `isSupportedComponentType`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, change the final return of `isSupportedComponentType` from:

```java
    return type.isPrimitive() || type.isNumeric() || type.isBool() || type.isStringForm() || type.isNested();
```

to:

```java
    return type.isPrimitive() || type.isNumeric() || type.isBool() || type.isStringForm() || type.hasCompanion();
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `latte test --test=PolyUsageTest`
Expected: PASS — all 4 tests green.

- [ ] **Step 8: Run the full suite**

Run: `latte test`
Expected: PASS — 194 tests (190 + 4), 0 failures. (Nested tests stay green: for records `hasCompanion()` equals `isNested()`.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/TypeView.java \
        src/main/jte/memberCall.jte src/main/jte/arrayAppend.jte src/main/jte/observerBody.jte \
        src/main/jte/arrayObserver.jte src/main/jte/mapObserver.jte \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/poly \
        src/test/java/org/lattejava/json/tests/processor/PolyUsageTest.java
git commit -m "feat: use polymorphic @JSON types as fields, list/set elements, and map values

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Compile-time validation

Reject malformed polymorphic hierarchies with clear messages.

**Files:**
- Create: rejection fixtures (each its own module) under `src/test/resources/fixtures/`:
  - `badpoly_nonsealed/`, `badpoly_missingjson/`, `badpoly_dupvalue/`, `badpoly_collision/`, `badpoly_orphan/`, `badpoly_notypeinfo/`
- Create: `src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Write the rejection fixtures**

`badpoly_nonsealed/module-info.java` → `module demo.badpoly_nonsealed { requires static org.lattejava.json; }` (use the same one-line module-info, with the matching module name, for every fixture below).

`badpoly_nonsealed/demo/NonSealed.java` (interface with `@JSONTypeInfo` but not sealed):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "t")
public interface NonSealed {
}
```

`badpoly_missingjson/demo/Base.java` + `Impl.java` (subtype missing `@JSON`):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "t")
public sealed interface Base permits Impl {
}
```

```java
package demo;

public record Impl(String x) implements Base {
}
```

`badpoly_dupvalue/demo/Base.java` + `A.java` + `B.java` (two subtypes, same discriminator value):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "t")
public sealed interface Base permits A, B {
}
```

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("same")
public record A(String x) implements Base {
}
```

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("same")
public record B(String y) implements Base {
}
```

`badpoly_collision/demo/Base.java` + `C.java` (discriminator property collides with a component name):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "kind")
public sealed interface Base permits C {
}
```

```java
package demo;

import module org.lattejava.json;

@JSON
public record C(String kind, String name) implements Base {
}
```

`badpoly_orphan/demo/Orphan.java` (`@JSONSubtype` with no `@JSONTypeInfo` parent):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("x")
public record Orphan(String x) {
}
```

`badpoly_notypeinfo/demo/Bare.java` + `BareImpl.java` (`@JSON` sealed interface without `@JSONTypeInfo`):

```java
package demo;

import module org.lattejava.json;

@JSON
public sealed interface Bare permits BareImpl {
}
```

```java
package demo;

import module org.lattejava.json;

@JSON
public record BareImpl(String x) implements Bare {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolyRejectionTest {
  private static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            java.util.Arrays.stream(needles).allMatch(d::contains)),
        "expected a diagnostic containing " + java.util.Arrays.toString(needles)
            + ", got: " + r.diagnostics());
  }

  @Test public void nonSealedRejected() throws Exception {
    assertFailsWith("badpoly_nonsealed", "sealed", "NonSealed");
  }

  @Test public void subtypeMissingJSONRejected() throws Exception {
    assertFailsWith("badpoly_missingjson", "@JSON", "Impl");
  }

  @Test public void duplicateDiscriminatorValueRejected() throws Exception {
    assertFailsWith("badpoly_dupvalue", "discriminator value", "same");
  }

  @Test public void discriminatorCollisionRejected() throws Exception {
    assertFailsWith("badpoly_collision", "discriminator", "kind");
  }

  @Test public void orphanSubtypeRejected() throws Exception {
    assertFailsWith("badpoly_orphan", "@JSONSubtype", "@JSONTypeInfo");
  }

  @Test public void interfaceWithoutTypeInfoRejected() throws Exception {
    assertFailsWith("badpoly_notypeinfo", "@JSONTypeInfo", "Bare");
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=PolyRejectionTest`
Expected: FAIL — most fixtures either compile (no validation yet) or fail with the wrong message. E.g. `badpoly_nonsealed` currently generates an empty-switch `NonSealedJSON` that may or may not compile; `badpoly_orphan` compiles (an orphan `@JSONSubtype` record is generated normally). The specific message assertions fail.

- [ ] **Step 4: Implement `validatePolymorphic` and the subtype/interface checks**

In `src/main/java/org/lattejava/json/JSONProcessor.java`:

**(a)** Reject a `@JSON` sealed/non-sealed interface that lacks `@JSONTypeInfo`. In `process()`, the guard now admits only `RECORD` or `INTERFACE + @JSONTypeInfo`. Add a clearer message for a `@JSON` interface missing `@JSONTypeInfo` by replacing the guard block from Task 1 Step 9(a) with:

```java
      TypeElement type = (TypeElement) e;
      boolean polyParent = e.getKind() == ElementKind.INTERFACE && type.getAnnotation(JSONTypeInfo.class) != null;
      if (e.getKind() == ElementKind.INTERFACE && type.getAnnotation(JSONTypeInfo.class) == null) {
        error(e, "@JSON interface [" + type.getQualifiedName() + "] requires @JSONTypeInfo to declare its discriminator");
        continue;
      }
      if (e.getKind() != ElementKind.RECORD && !polyParent) {
        error(e, "@JSON supports only records and sealed @JSONTypeInfo interfaces in this release; ["
            + qualified(e) + "] is a [" + e.getKind() + "]");
        continue;
      }
```

**(b)** Guard `generatePolymorphic` with `validatePolymorphic`. In `process()`, change the `polyParent` branch to:

```java
      if (polyParent) {
        if (!validatePolymorphic(type)) {
          continue;
        }
        if (!helpersEmitted) {
          emitHelpers(module);
          helpersEmitted = true;
        }
        generatePolymorphic(type, module);
        continue;
      }
```

**(c)** Add `validatePolymorphic` (place it before `validateComponents`):

```java
  private boolean validatePolymorphic(TypeElement iface) {
    boolean ok = true;
    if (!iface.getModifiers().contains(Modifier.SEALED)) {
      error(iface, "@JSONTypeInfo type [" + iface.getQualifiedName() + "] must be a sealed interface");
      return false;
    }

    String property = iface.getAnnotation(JSONTypeInfo.class).property();
    Map<String, String> seenValues = new HashMap<>();
    for (TypeMirror permitted : iface.getPermittedSubclasses()) {
      TypeElement sub = (TypeElement) ((javax.lang.model.type.DeclaredType) permitted).asElement();
      if (sub.getAnnotation(JSON.class) == null) {
        error(iface, "permitted subtype [" + sub.getQualifiedName() + "] of @JSONTypeInfo type ["
            + iface.getQualifiedName() + "] must be annotated @JSON");
        ok = false;
        continue;
      }

      String value = discriminatorValueOf(sub);
      String prior = seenValues.put(value, sub.getSimpleName().toString());
      if (prior != null) {
        error(iface, "duplicate discriminator value [" + value + "] on subtypes [" + prior + "] and ["
            + sub.getSimpleName() + "] of @JSONTypeInfo type [" + iface.getQualifiedName() + "]");
        ok = false;
      }

      for (RecordComponentElement c : sub.getRecordComponents()) {
        if (c.getSimpleName().toString().equals(property)) {
          error(iface, "discriminator property [" + property + "] collides with component [" + c.getSimpleName()
              + "] on subtype [" + sub.getSimpleName() + "]");
          ok = false;
        }
      }
    }
    return ok;
  }
```

**(d)** Reject an orphan `@JSONSubtype` (a record annotated `@JSONSubtype` whose interfaces include no `@JSONTypeInfo` parent). In `generateCompanion`, after computing `discriminatorKey`/`discriminatorValue` (Task 1 Step 9(b)), add — before constructing the `CompanionView`:

```java
    if (discriminatorKey.isEmpty() && record.getAnnotation(JSONSubtype.class) != null) {
      error(record, "@JSONSubtype on [" + record.getQualifiedName()
          + "] requires an implemented @JSONTypeInfo interface");
      return;
    }
```

Ensure `Modifier`, `HashMap`, and `Map` resolve — `Modifier` is in `javax.lang.model.element` (covered by `import module java.compiler`); `HashMap`/`Map` by `import module java.base`. Confirm the existing imports at the top of `JSONProcessor.java` already include these module imports (they do).

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=PolyRejectionTest`
Expected: PASS — all 6 tests green.

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS — 200 tests (194 + 6), 0 failures. `PolyCodegenTest`/`PolyUsageTest` stay green (the `poly` fixture is well-formed, so `validatePolymorphic` passes it).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/badpoly_nonsealed src/test/resources/fixtures/badpoly_missingjson \
        src/test/resources/fixtures/badpoly_dupvalue src/test/resources/fixtures/badpoly_collision \
        src/test/resources/fixtures/badpoly_orphan src/test/resources/fixtures/badpoly_notypeinfo \
        src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java
git commit -m "feat: compile-time validation for polymorphic @JSON hierarchies

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full suite + targeted checks**

Run: `latte test`
Expected: PASS — 200 tests, 0 failures. Explicitly confirm green: `PolyCodegenTest`, `PolyUsageTest`, `PolyRejectionTest`, plus the prior `NestedCodegenTest`, `NestedRejectionTest`, `MapCodegenTest`, `ListCodegenTest`, `SetCodegenTest`, `HelperEmissionTest`, `ProcessorErrorsTest`.

- [ ] **Step 2: Spot-check a generated polymorphic companion**

Run: `find build/test/generated/poly -name 'PetJSON.java' -print -exec cat {} \;`
Expected: `PetJSON implements JSONPolymorphicObserver<Pet>`, `observerFor` switch over `Dog`/`kitty`/`Bird` returning `new demo.internal.*JSON()`, exhaustive `toJSON`/`toJSONBytes` switches, `fromJSON` via `parsePolymorphic`. Confirm `DogJSON.java`'s `builder(...)` starts with `.string("petType", "Dog")`.

- [ ] **Step 3: No commit needed** (verification only). If any check fails, surface it to the reviewer rather than patching silently.

---

## Self-Review

**Spec coverage:**
- §1 polymorphic-type usage composes with nested → Task 2 (`hasCompanion()`, the five template swaps, `isSupportedComponentType`).
- §2 dispatcher companion (`discriminatorKey`/`observerFor`/`fromJSON`/`toJSON`/`toJSONBytes`) → Task 1 (`polymorphic.jte`, `PolymorphicView`, `generatePolymorphic`).
- §3 discriminator-first subtype serialization → Task 1 (`CompanionView.discriminatorKey/Value`, `companion.jte`, subtype discriminator computation).
- §4 subtype observer ignores its discriminator key → Task 1 (`observerBody.jte` arm; `strictSubtypeIgnoresDiscriminatorOnDirectParse`).
- §5 validation (non-sealed, subtype-missing-`@JSON`, duplicate value, collision, orphan `@JSONSubtype`, interface-without-`@JSONTypeInfo`) → Task 3.
- §6 files touched, §7 conventions → addressed across tasks; the relaxed records-only guard is in Task 1 Step 9(a)/Task 3 Step 4(a).
- §Testing → Task 1 (root, discriminator-first/last, custom/default value, unknown/missing, strict-ignore), Task 2 (field/list/map/nested), Task 3 (rejections).

**Placeholder scan:** none — every fixture, test, template, and processor edit is complete code or an exact before/after.

**Type consistency:** `PolymorphicView` + nested `Subtype(value, typeFqn, companionFqn)` defined in Task 1 Step 5, consumed by `polymorphic.jte` (Step 6) and `generatePolymorphic` (Step 9c). `CompanionView` gains `discriminatorKey`/`discriminatorValue` in Step 4, set in Step 9b, read by `companion.jte`/`observerBody.jte` (Steps 7-8). `discriminatorValueOf` defined Step 9c, reused in Task 3 `validatePolymorphic`. `TypeView.isPolymorphic()`/`hasCompanion()` defined Task 2 Step 4, used in Steps 5-6. `generatePolymorphic`/`validatePolymorphic` signatures consistent between Task 1 and Task 3.
