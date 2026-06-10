# Polymorphism for Non-record Subtypes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `@JSONConstructor` classes and JavaBeans (not just records) as permitted subtypes of a sealed `@JSONTypeInfo` interface.

**Architecture:** The dispatcher (`polymorphic.jte`), subtype-companion generation (`CompanionWriter`), and `@JSONSubtype` are already kind-agnostic — only `PolymorphicValidator` blocks class subtypes. Relax its record-only kind check, and generalize its discriminator-key collision check to enumerate a class subtype's members.

**Tech Stack:** Java 25 annotation processor, JTE templates, Latte build (`latte clean && latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-10-polymorphism-nonrecord-subtypes-design.md`

---

## Notes (read first)

- **Only one production file changes:** `PolymorphicValidator` (in `org.lattejava.json.processor`). Everything downstream is already kind-agnostic — a class subtype's companion (with discriminator) and the dispatcher's `case <SubtypeFqn> v -> <SubtypeJSON>.toJSON(v)` / `observerFor → new <Subtype>JSON()` already work.
- **`latte clean` before EVERY `latte test`** — stale incremental descriptors otherwise.
- Class subtypes must be `final`/`sealed`/`non-sealed` (javac enforces sealing; the processor never sees an ill-sealed hierarchy). New fixtures mark class subtypes `final`.
- New fixtures: `.java`, match siblings (NO SPDX header; `import module org.lattejava.json;`). Reject-test needles bracketed, via `PolyRejectionTest`'s `assertFailsWith`.

---

## Task 1: Accept class/bean subtypes (the happy path)

Relax the record-only kind check so class subtypes round-trip; the collision check (still record-only) doesn't fire for the non-colliding class subtypes.

**Files:**
- Modify: `src/main/java/org/lattejava/json/processor/PolymorphicValidator.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java`
- Create: `src/test/resources/fixtures/polysub/` + `PolySubCodegenTest.java`

- [ ] **Step 1: Write the `polysub` fixture**

`src/test/resources/fixtures/polysub/module-info.java`:

```java
module demo.polysub {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/polysub/demo/Shape.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "kind")
public sealed interface Shape permits Circle, Square, Note {
}
```

`src/test/resources/fixtures/polysub/demo/Circle.java` (record subtype):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("circle")
public record Circle(int radius) implements Shape {
}
```

`src/test/resources/fixtures/polysub/demo/Square.java` (`@JSONConstructor` class subtype):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("square")
public final class Square implements Shape {
  private final int side;

  @JSONConstructor
  public Square(int side) {
    this.side = side;
  }

  public int getSide() {
    return side;
  }
}
```

`src/test/resources/fixtures/polysub/demo/Note.java` (JavaBean subtype):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("note")
public final class Note implements Shape {
  private String text;

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }
}
```

`src/test/resources/fixtures/polysub/demo/Drawing.java` (a record nesting the polymorphic type):

```java
package demo;

import module org.lattejava.json;

@JSON
public record Drawing(String title, Shape shape) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/PolySubCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolySubCodegenTest {
  static ProcessorHarness.Result polysub;

  @BeforeClass
  public void compileOnce() throws Exception {
    polysub = ProcessorHarness.compile("polysub");
    assertTrue(polysub.success(), polysub.diagnostics().toString());
  }

  @Test
  public void recordSubtypeRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> shape = loader.loadClass("demo.Shape");
      Class<?> shapeJson = loader.loadClass("demo.internal.ShapeJSON");
      String json = "{\"kind\":\"circle\",\"radius\":3}";
      Object o = shapeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Circle").getMethod("radius").invoke(o), 3);
      assertEquals(shapeJson.getMethod("toJSON", shape).invoke(null, o), json);
    }
  }

  @Test
  public void constructorClassSubtypeRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> shape = loader.loadClass("demo.Shape");
      Class<?> shapeJson = loader.loadClass("demo.internal.ShapeJSON");
      String json = "{\"kind\":\"square\",\"side\":2}";
      Object o = shapeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Square").getMethod("getSide").invoke(o), 2);
      assertEquals(shapeJson.getMethod("toJSON", shape).invoke(null, o), json);
    }
  }

  @Test
  public void beanSubtypeRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> shape = loader.loadClass("demo.Shape");
      Class<?> shapeJson = loader.loadClass("demo.internal.ShapeJSON");
      String json = "{\"kind\":\"note\",\"text\":\"hi\"}";
      Object o = shapeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Note").getMethod("getText").invoke(o), "hi");
      assertEquals(shapeJson.getMethod("toJSON", shape).invoke(null, o), json);
    }
  }

  @Test
  public void classSubtypeNestedInRecord() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> drawing = loader.loadClass("demo.Drawing");
      Class<?> drawingJson = loader.loadClass("demo.internal.DrawingJSON");
      String json = "{\"title\":\"t\",\"shape\":{\"kind\":\"square\",\"side\":2}}";
      Object o = drawingJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(drawingJson.getMethod("toJSON", drawing).invoke(null, o), json);
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte clean && latte test --test=PolySubCodegenTest`
Expected: FAIL — `@BeforeClass` compile fails: `Square`/`Note` are non-record subtypes, rejected by `PolymorphicValidator` ("must be a record").

- [ ] **Step 4: Relax the kind check in `PolymorphicValidator`**

In `src/main/java/org/lattejava/json/processor/PolymorphicValidator.java`, replace the record-only check:

```java
      if (sub.getKind() != ElementKind.RECORD) {
        error(iface, "permitted subtype [" + sub.getQualifiedName() + "] of @JSONTypeInfo type ["
            + iface.getQualifiedName() + "] must be a record");
        ok = false;
        continue;
      }
```

with:

```java
      if (sub.getKind() != ElementKind.RECORD && sub.getKind() != ElementKind.CLASS) {
        error(iface, "permitted subtype [" + sub.getQualifiedName() + "] of @JSONTypeInfo type ["
            + iface.getQualifiedName() + "] must be a record or class");
        ok = false;
        continue;
      }
```

- [ ] **Step 5: Update the now-misnamed interface-subtype rejection test**

In `src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java`, the existing `nonRecordSubtypeRejected` uses `badpoly_nonrecordsub`, whose subtype `Mid` is an **interface** (a nested polymorphic parent) — still rejected (it is neither a record nor a class), now with the "or class" message. Rename it and tighten the needle:

```java
  @Test public void interfaceSubtypeRejected() throws Exception {
    assertFailsWith("badpoly_nonrecordsub", "must be a record or class", "Mid");
  }
```

- [ ] **Step 6: Run the tests, then the full suite**

Run: `latte clean && latte test --test=PolySubCodegenTest` then `latte test --test=PolyRejectionTest`
Expected: PASS — the four round-trips green; `interfaceSubtypeRejected` green.

Run: `latte clean && latte test`
Expected: PASS — **269 tests** (265 + 4), 0 failures. Existing record hierarchies unchanged (the kind check still admits records; the collision check is untouched).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/processor/PolymorphicValidator.java \
        src/test/resources/fixtures/polysub \
        src/test/java/org/lattejava/json/tests/processor/PolySubCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java
git commit -m "feat: allow class/bean subtypes in @JSONTypeInfo hierarchies

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Generalize the discriminator-collision check to class subtypes

A class subtype with a member whose wire key equals the discriminator `property` must be rejected (it would emit two values under one key). The current check iterates `getRecordComponents()` (empty for classes), so it must enumerate constructor parameters / bean properties too.

**Files:**
- Modify: `src/main/java/org/lattejava/json/processor/PolymorphicValidator.java`, `JSONProcessor.java`
- Create: reject fixtures + `PolyRejectionTest.java` additions

- [ ] **Step 1: Write the failing reject fixtures + tests**

`src/test/resources/fixtures/badpolysub_ctorcollision/module-info.java` → `module demo.badpolysub_ctorcollision { requires static org.lattejava.json; }`; `demo/Box.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "kind")
public sealed interface Box permits BadCtor {
}
```

`demo/BadCtor.java` (a `@JSONConstructor` param whose wire key collides with `kind`):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("bc")
public final class BadCtor implements Box {
  private final String kind;

  @JSONConstructor
  public BadCtor(String kind) {
    this.kind = kind;
  }

  public String getKind() {
    return kind;
  }
}
```

`src/test/resources/fixtures/badpolysub_beancollision/module-info.java` → `module demo.badpolysub_beancollision { requires static org.lattejava.json; }`; `demo/Crate.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "kind")
public sealed interface Crate permits BadBean {
}
```

`demo/BadBean.java` (a bean property whose wire key collides with `kind`):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("bb")
public final class BadBean implements Crate {
  private String kind;

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }
}
```

Add to `src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java`:

```java
  @Test public void constructorParamDiscriminatorCollisionRejected() throws Exception {
    assertFailsWith("badpolysub_ctorcollision", "discriminator property", "kind");
  }

  @Test public void beanPropertyDiscriminatorCollisionRejected() throws Exception {
    assertFailsWith("badpolysub_beancollision", "discriminator property", "kind");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte clean && latte test --test=PolyRejectionTest`
Expected: FAIL — `badpolysub_ctorcollision`/`badpolysub_beancollision` currently **compile** (the collision check skips class subtypes), so `r.success()` is true and `assertFalse` fails.

- [ ] **Step 3: Inject `ClassMemberDiscovery` into `PolymorphicValidator`**

In `PolymorphicValidator.java`, change the constructor to take and store `ClassMemberDiscovery` (same package — no import). Replace:

```java
public final class PolymorphicValidator extends AbstractValidator {
  public PolymorphicValidator(ProcessingEnvironment processingEnv) {
    super(processingEnv);
  }
```

with:

```java
public final class PolymorphicValidator extends AbstractValidator {
  private final ClassMemberDiscovery members;

  public PolymorphicValidator(ProcessingEnvironment processingEnv, ClassMemberDiscovery members) {
    super(processingEnv);
    this.members = members;
  }
```

In `JSONProcessor.init()`, update the construction (it already builds `members` before the validators):

```java
    this.polymorphicValidator = new PolymorphicValidator(processingEnv, members);
```

- [ ] **Step 4: Generalize the collision check**

In `PolymorphicValidator.validate`, replace the record-only collision loop:

```java
      NamingStrategy subNaming = ProcessorFacts.naming(sub);
      for (RecordComponentElement c : sub.getRecordComponents()) {
        if (Component.wireKey(c, subNaming).equals(property)) {
          error(iface, "discriminator property [" + property + "] collides with the JSON key of component ["
              + c.getSimpleName() + "] on subtype [" + sub.getSimpleName() + "]");
          ok = false;
        }
      }
```

with a per-kind enumeration that uses a small `collides` helper:

```java
      NamingStrategy subNaming = ProcessorFacts.naming(sub);
      if (sub.getKind() == ElementKind.RECORD) {
        for (RecordComponentElement c : sub.getRecordComponents()) {
          ok &= !collides(iface, property, c.getSimpleName().toString(), Component.wireKey(c, subNaming), sub);
        }
      } else if (members.isBean(sub)) {
        for (ClassMemberDiscovery.BeanProperty bp : members.discoverProperties(sub)) {
          JSONField pf = bp.config() == null ? null : bp.config().getAnnotation(JSONField.class);
          String wireKey = pf != null && !pf.name().isEmpty() ? pf.name()
              : NamingStrategies.apply(subNaming, bp.name());
          ok &= !collides(iface, property, bp.name(), wireKey, sub);
        }
      } else {
        for (VariableElement p : members.jsonConstructors(sub).getFirst().getParameters()) {
          ok &= !collides(iface, property, p.getSimpleName().toString(), Component.wireKey(p, subNaming), sub);
        }
      }
```

Add the `collides` helper (alphabetical — before `validate`):

```java
  /** Reports (and returns true) if {@code wireKey} equals the discriminator {@code property}. */
  private boolean collides(TypeElement iface, String property, String memberName, String wireKey, TypeElement sub) {
    if (wireKey.equals(property)) {
      error(iface, "discriminator property [" + property + "] collides with the JSON key of member ["
          + memberName + "] on subtype [" + sub.getSimpleName() + "]");
      return true;
    }
    return false;
  }
```

Add the imports `import org.lattejava.json.JSONField;` and `import org.lattejava.json.NamingStrategies;` to `PolymorphicValidator`.

(Note: `ok &= !collides(...)` keeps the original accumulate-don't-short-circuit behavior — every colliding member is reported. The record branch is logically identical to the original.)

- [ ] **Step 5: Run the reject tests, then the full suite**

Run: `latte clean && latte test --test=PolyRejectionTest`
Expected: PASS — the two new collision rejections green, plus the existing record-collision rejections (`discriminatorCollisionRejected`, `renamedDiscriminatorCollisionRejected`) still green (the record branch is unchanged in behavior; the message noun "component"→"member" doesn't affect their needles, which assert `discriminator`/`discriminator property` + `kind`).

Run: `latte clean && latte test`
Expected: PASS — **271 tests** (269 + 2), 0 failures.

- [ ] **Step 6: Spot-check a generated class-subtype companion**

Run: `find build/test/generated/polysub -name 'SquareJSON.java' -exec cat {} \;`
Expected: `SquareJSON` emits the discriminator first (`.string("kind", "square")`) in `builder`, the observer has a `case "kind" -> { /* discriminator: ignore */ }`, and `finish()` is `new Square(this.side)`. Confirms a class subtype's companion is discriminator-aware exactly like a record subtype's.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/processor/PolymorphicValidator.java \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/badpolysub_ctorcollision \
        src/test/resources/fixtures/badpolysub_beancollision \
        src/test/java/org/lattejava/json/tests/processor/PolyRejectionTest.java
git commit -m "feat: enforce discriminator-key collision check on class/bean subtypes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §"Why this is (almost) free" → confirmed: only `PolymorphicValidator` changes; dispatcher/`CompanionWriter`/`@JSONSubtype` untouched.
- §1 accept class subtypes → Task 1 Step 4 (kind check).
- §2 generalize collision check (record / `@JSONConstructor` param / bean property wire keys) → Task 2 Step 4.
- §3 files (`PolymorphicValidator` + `JSONProcessor.init` wiring) → Tasks 1 & 2.
- §Testing: round-trip + dispatch (record/class/bean) → Task 1; class subtype nested → Task 1 (`classSubtypeNestedInRecord`); rejections (ctor-param collision, bean-property collision, non-record-non-class subtype) → Task 2 + the renamed `interfaceSubtypeRejected` (Task 1). (An enum subtype is the same "neither record nor class" branch as the interface fixture; not separately fixtured.)
- §Non-goals (class-rooted hierarchies; no wire-format change) → respected (no template change).

**Placeholder scan:** none — every step is complete code or an exact before/after.

**Type consistency:** `PolymorphicValidator(ProcessingEnvironment, ClassMemberDiscovery)` (Task 2 Step 3) matches the `JSONProcessor.init` call. `collides(TypeElement, String, String, String, TypeElement)` (Step 4) used by all three branches. `ClassMemberDiscovery.BeanProperty.config()`/`name()` + `members.isBean`/`discoverProperties`/`jsonConstructors` are the existing signatures. Bean wire-key formula (`@JSONField(name)` else `NamingStrategies.apply(naming, name)`) matches `ClassValidator.validateBean`. Test counts: 265 → 269 (T1) → 271 (T2).
