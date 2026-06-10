# @JSONField Policies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen for the `@JSONField` representation attributes — `ignore`, `readOnly`, `writeOnly`, `format`, and a new `instant` (epoch-integer `Instant`) — and remove the unimplemented `required` attribute.

**Architecture:** `Component` reads its `@JSONField` once into policy facts; the templates filter serialize/deserialize call sites by `serialize()`/`deserialize()` predicates, route `format` components through a per-field `DateTimeFormatter`, and route `instant != ISO` components through the JSON-integer path. Validation of contradictory combinations lands in `JSONProcessor.validateComponents`. `IDENTITY`-style defaults keep every existing type byte-identical.

**Tech Stack:** Java 25, JTE 3.2.1 templates, `javax.annotation.processing`, Latte build (`latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-07-jsonfield-policies-design.md`

---

## Notes (read first)

- **Representation only.** `required` is dropped (Task 1 removes the attribute). Cycle B is `ignore`/`readOnly`/`writeOnly`/`format`/`instant`.
- **What stays the Java name vs wire key:** unchanged from Cycle A — `wireKey()` is the JSON key, `name()` the Java field/accessor.
- **Direction:** `serialize() = !ignore && !writeOnly` (in `toJSON`); `deserialize() = !ignore && !readOnly` (in the observer). `finish()` still constructs **all** record components.
- **format/instant exclusivity:** a component is at most one of "formatted" or "epoch-instant" (enforced by validation), and both are java.time-only, so the template branches don't overlap.

---

## File Structure

**Create:**
- `src/main/java/org/lattejava/json/InstantFormat.java` — the `EPOCH_MILLIS`/`EPOCH_SECONDS`/`ISO` enum.
- `src/test/resources/fixtures/policies/` — fixture (module `demo.policies`) + reject fixtures.
- `src/test/java/org/lattejava/json/tests/processor/PolicyCodegenTest.java` and `PolicyRejectionTest.java`.

**Modify:**
- `src/main/java/org/lattejava/json/JSONField.java` — drop `required()`, add `instant()`.
- `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java` — swap the `required` assertion for `instant`.
- `src/main/java/org/lattejava/json/jte/Component.java` — read the policy attributes; expose `serialize()`/`deserialize()`/`isFormatted()`/`format()`/`formatterField()`/`formatType()`/`formatNeedsZone()`/`isEpochInstant()`/`epochAccessor()`/`epochFactory()`.
- `src/main/jte/companion.jte` — formatter fields; filter builder by `serialize()`; format/epoch routing.
- `src/main/jte/observerBody.jte` — filter case labels by `deserialize()`; format/epoch routing.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — policy validation in `validateComponents`.
- `docs/design/2026-05-12-serialization.md` — supersede the `required` references.

**Acceptance gate every task:** full suite green — `latte test` (currently 219).

---

## Task 1: Annotation surface — drop `required`, add `instant`

**Files:**
- Create: `src/main/java/org/lattejava/json/InstantFormat.java`
- Modify: `src/main/java/org/lattejava/json/JSONField.java`
- Modify: `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`
- Modify: `docs/design/2026-05-12-serialization.md`

- [ ] **Step 1: Update the annotation-declaration test (TDD — it will fail to compile until the annotation changes)**

In `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`, replace the `jsonFieldAnnotationHasExpectedAttributes` method body's `required` line with an `instant` line:

Replace:
```java
    assertEquals(ann.getDeclaredMethod("readOnly").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("required").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("writeOnly").getDefaultValue(), Boolean.FALSE);
```
with:
```java
    assertEquals(ann.getDeclaredMethod("instant").getDefaultValue(), InstantFormat.ISO);
    assertEquals(ann.getDeclaredMethod("readOnly").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("writeOnly").getDefaultValue(), Boolean.FALSE);
```

(`InstantFormat` resolves via the existing `import module org.lattejava.json;`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=AnnotationDeclarationTest`
Expected: FAIL — compilation fails: `JSONField` has no `instant` method yet and `InstantFormat` does not exist.

- [ ] **Step 3: Create the `InstantFormat` enum**

`src/main/java/org/lattejava/json/InstantFormat.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Wire representation for an {@code Instant} {@code @JSON} record component. {@code ISO} (the default) is the
 * ISO-8601 string form (or the {@link JSONField#format()} pattern when set); {@code EPOCH_SECONDS} and
 * {@code EPOCH_MILLIS} are JSON integers counting from the epoch.
 *
 * @author Brian Pontarelli
 */
public enum InstantFormat {
  EPOCH_MILLIS,
  EPOCH_SECONDS,
  ISO
}
```

- [ ] **Step 4: Drop `required`, add `instant` on `JSONField`**

Replace `src/main/java/org/lattejava/json/JSONField.java` with:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Per-field configuration for a record component of an {@link JSON @JSON}-annotated type.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface JSONField {
  String format() default "";

  boolean ignore() default false;

  InstantFormat instant() default InstantFormat.ISO;

  String name() default "";

  boolean readOnly() default false;

  boolean writeOnly() default false;
}
```

- [ ] **Step 5: Supersede the `required` references in the original design doc**

In `docs/design/2026-05-12-serialization.md`, find the `@JSONField` attribute block and the "Missing JSON fields" subsection. Replace the `required` attribute line in the annotation code block:

```java
  boolean required() default false;  // throw on missing during deserialization
```
with:
```java
  InstantFormat instant() default InstantFormat.ISO;  // epoch-integer Instant representation
```

And replace the `**required**` description paragraph (the one beginning "**`required`** — codegen tracks whether the field was set…") with:

```markdown
**`required`** — removed (see `docs/design/2026-06-07-jsonfield-policies-design.md`, "Dropped: `required`"). Presence-checking is a caller concern; a missing field keeps the lenient default (primitives at their Java default, references `null`).
```

(If the "Missing JSON fields" subsection also says required tightens to a throw, leave a one-line note there pointing at the policies doc. This is documentation only — no behavior depends on it.)

- [ ] **Step 6: Run the test to verify it passes**

Run: `latte test --test=AnnotationDeclarationTest`
Expected: PASS — `instant` default is `InstantFormat.ISO`, `required` is gone.

- [ ] **Step 7: Run the full suite**

Run: `latte test`
Expected: PASS — 219 tests, 0 failures. Nothing reads `@JSONField.required()` in codegen, so removing it changes no generated output.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/lattejava/json/InstantFormat.java \
        src/main/java/org/lattejava/json/JSONField.java \
        src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java \
        docs/design/2026-05-12-serialization.md
git commit -m "feat: drop @JSONField.required, add InstantFormat + @JSONField.instant

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Direction filtering (`ignore` / `readOnly` / `writeOnly`)

`Component` reads all policy attributes; the templates filter call sites by `serialize()`/`deserialize()`; validation rejects `readOnly`+`writeOnly` and `ignore`+other.

**Files:**
- Modify: `src/main/java/org/lattejava/json/jte/Component.java`
- Modify: `src/main/jte/companion.jte`, `src/main/jte/observerBody.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Create: `src/test/resources/fixtures/policies/` (module + `Directions.java`), `PolicyCodegenTest.java`, reject fixtures + `PolicyRejectionTest.java`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/policies/module-info.java`:

```java
module demo.policies {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/policies/demo/Directions.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Directions(
    String both,
    @JSONField(readOnly = true) String readOnly,
    @JSONField(writeOnly = true) String writeOnly,
    @JSONField(ignore = true) String ignored) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/PolicyCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolicyCodegenTest {
  static ProcessorHarness.Result policies;

  @BeforeClass
  public void compileOnce() throws Exception {
    policies = ProcessorHarness.compile("policies");
    assertTrue(policies.success(), policies.diagnostics().toString());
  }

  @Test
  public void readOnlySerializedNotDeserialized() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> t = loader.loadClass("demo.Directions");
      Class<?> j = loader.loadClass("demo.internal.DirectionsJSON");
      // readOnly + writeOnly + ignored all parse-absent; only `both` and `readOnly` round on the read side.
      Object o = j.getMethod("fromJSON", String.class)
          .invoke(null, "{\"both\":\"b\",\"readOnly\":\"r\",\"writeOnly\":\"w\",\"ignored\":\"i\"}");
      // readOnly is NOT deserialized -> stays null; writeOnly IS deserialized; ignored is NOT.
      assertEquals(t.getMethod("both").invoke(o), "b");
      assertNull(t.getMethod("readOnly").invoke(o), "readOnly must not be read from input");
      assertEquals(t.getMethod("writeOnly").invoke(o), "w");
      assertNull(t.getMethod("ignored").invoke(o), "ignored must not be read from input");
    }
  }

  @Test
  public void serializeOmitsWriteOnlyAndIgnored() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> t = loader.loadClass("demo.Directions");
      Class<?> j = loader.loadClass("demo.internal.DirectionsJSON");
      Object o = t.getConstructor(String.class, String.class, String.class, String.class)
          .newInstance("b", "r", "w", "i");
      // readOnly IS serialized; writeOnly and ignored are NOT.
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"both\":\"b\",\"readOnly\":\"r\"}");
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=PolicyCodegenTest`
Expected: FAIL — `Component` has no `serialize()`/`deserialize()`, and the templates don't filter, so `toJSON` includes all four keys and `writeOnly` isn't excluded.

- [ ] **Step 4: Add the policy model to `Component`**

Replace `src/main/java/org/lattejava/json/jte/Component.java` with:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

import org.lattejava.json.InstantFormat;
import org.lattejava.json.JSONField;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;

/**
 * Template-facing view of one {@code @JSON} record component: its Java name, its wire key, the {@link TypeView} facts
 * about its declared type, and its {@code @JSONField} policy facts (direction, format, instant). All serializer/observer
 * code is assembled from these facts in the JTE templates — there is no code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final String format;
  private final boolean ignore;
  private final InstantFormat instant;
  private final String name;
  private final boolean readOnly;
  private final TypeView type;
  private final String wireKey;
  private final boolean writeOnly;

  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element, NamingStrategy naming) {
    JSONField field = element.getAnnotation(JSONField.class);
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
    this.wireKey = wireKey(element, naming);
    this.ignore = field != null && field.ignore();
    this.readOnly = field != null && field.readOnly();
    this.writeOnly = field != null && field.writeOnly();
    this.format = field == null ? "" : field.format();
    this.instant = field == null ? InstantFormat.ISO : field.instant();
  }

  /**
   * Resolves the JSON wire key for {@code element}: an explicit {@code @JSONField(name)} verbatim, else {@code naming}
   * applied to the Java name.
   */
  public static String wireKey(RecordComponentElement element, NamingStrategy naming) {
    JSONField field = element.getAnnotation(JSONField.class);
    String override = field == null ? "" : field.name();
    return override.isEmpty() ? NamingStrategies.apply(naming, element.getSimpleName().toString()) : override;
  }

  /** Whether this component is deserialized (appears in the observer): not ignored and not read-only. */
  public boolean deserialize() {
    return !ignore && !readOnly;
  }

  /** The {@code Instant.ofEpoch*} factory for an epoch-instant component (deserialize). */
  public String epochFactory() {
    return instant == InstantFormat.EPOCH_MILLIS ? "ofEpochMilli" : "ofEpochSecond";
  }

  /** The {@code Instant} accessor (e.g. {@code toEpochMilli}) for an epoch-instant component (serialize). */
  public String epochMethod() {
    return instant == InstantFormat.EPOCH_MILLIS ? "toEpochMilli" : "getEpochSecond";
  }

  public String format() {
    return format;
  }

  /** Whether the format pattern's {@code DateTimeFormatter} needs a zone to resolve (true only for {@code Instant}). */
  public boolean formatNeedsZone() {
    return type.simpleName().equals("Instant");
  }

  /** The simple type name used for the formatter's {@code parse(value, <Type>::from)} query and field declaration. */
  public String formatType() {
    return type.simpleName();
  }

  /** The generated static formatter field name for a formatted component. */
  public String formatterField() {
    return name + "Formatter";
  }

  public boolean isEpochInstant() {
    return instant != InstantFormat.ISO;
  }

  public boolean isFormatted() {
    return !format.isEmpty();
  }

  public String name() {
    return name;
  }

  /** Whether this component is serialized (appears in {@code toJSON}): not ignored and not write-only. */
  public boolean serialize() {
    return !ignore && !writeOnly;
  }

  public TypeView type() {
    return type;
  }

  public String wireKey() {
    return wireKey;
  }
}
```

- [ ] **Step 5: Filter the builder by `serialize()` in `companion.jte`**

In `src/main/jte/companion.jte`, wrap the per-component builder block in `@if(c.serialize())`. Replace:

```jte
@for(Component c : view.components())
@if(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.wireKey() + "\"", val = "value." + c.name() + "()")
@endif
@endfor
```

with:

```jte
@for(Component c : view.components())
@if(c.serialize())
@if(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.wireKey() + "\"", val = "value." + c.name() + "()")
@endif
@endif
@endfor
```

- [ ] **Step 6: Filter the observer case labels by `deserialize()` in `observerBody.jte`**

In `src/main/jte/observerBody.jte`, add `c.deserialize() && ` to the front of every per-component `@if`/`@elseif` condition that guards a `case` label. There are these conditions to change:

- `string(...)`: `@if(c.type().isStringForm())` → `@if(c.deserialize() && c.type().isStringForm())`
- `integer(...)`: `@if(c.type().isNumeric())` → `@if(c.deserialize() && c.type().isNumeric())`
- `bigInteger(...)`: `@if(c.type().isNumeric())` → `@if(c.deserialize() && c.type().isNumeric())`
- `decimal(...)`: `@if(c.type().isNumeric())` → `@if(c.deserialize() && c.type().isNumeric())`
- `bool(...)`: `@if(c.type().isBool())` → `@if(c.deserialize() && c.type().isBool())`
- `nullValue(...)`: `@if(c.type().isPrimitive())` → `@if(c.deserialize() && c.type().isPrimitive())` (the `@else` arm then needs its own guard — see below)
- `beginObject(...)`: `@if(c.type().isMap())` → `@if(c.deserialize() && c.type().isMap())`; `@elseif(c.type().hasCompanion())` → `@elseif(c.deserialize() && c.type().hasCompanion())`
- `object(...)`: `@if(c.type().isMap() || c.type().hasCompanion())` → `@if(c.deserialize() && (c.type().isMap() || c.type().hasCompanion()))`
- `beginArray(...)`: `@if(c.type().isList() || c.type().isSet())` → `@if(c.deserialize() && (c.type().isList() || c.type().isSet()))`
- `array(...)`: `@if(c.type().isList() || c.type().isSet())` → `@if(c.deserialize() && (c.type().isList() || c.type().isSet()))`

For `nullValue(...)`, the current `@if(primitive) … @else … @endif` must not emit ANY case for a non-deserialized component, so restructure that one block to:

```jte
  @Override public void nullValue(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.deserialize() && c.type().isPrimitive())
      case "${c.wireKey()}" -> throw new JSONProcessingException("null for primitive field [${c.name()}]");
@elseif(c.deserialize())
      case "${c.wireKey()}" -> this.${c.name()} = null;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
```

- [ ] **Step 7: Validate `readOnly`+`writeOnly` and `ignore`+other in `JSONProcessor`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, in `validateComponents`, add a policy check at the top of the per-component loop, right after the existing wire-key character/duplicate checks and before the `TypeView type = …` line. Insert:

```java
      JSONField policy = c.getAnnotation(JSONField.class);
      if (policy != null) {
        if (policy.readOnly() && policy.writeOnly()) {
          error(c, "@JSONField component [" + c.getSimpleName() + "] is both readOnly and writeOnly (equivalent to ignore)");
          ok = false;
          continue;
        }
        if (policy.ignore() && (!policy.name().isEmpty() || !policy.format().isEmpty()
            || policy.readOnly() || policy.writeOnly() || policy.instant() != InstantFormat.ISO)) {
          error(c, "@JSONField component [" + c.getSimpleName() + "] combines ignore with another attribute, which has no effect");
          ok = false;
          continue;
        }
      }
```

(`JSONField`, `InstantFormat` are in `org.lattejava.json`, the same package as `JSONProcessor` — no import needed.)

- [ ] **Step 8: Run the codegen test to verify it passes**

Run: `latte test --test=PolicyCodegenTest`
Expected: PASS — both tests green (`readOnly` serialized-not-read, `writeOnly`/`ignored` not serialized).

- [ ] **Step 9: Write the rejection fixtures + test**

`src/test/resources/fixtures/badpolicy_rwconflict/module-info.java` → `module demo.badpolicy_rwconflict { requires static org.lattejava.json; }`; `demo/RW.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record RW(@JSONField(readOnly = true, writeOnly = true) String x) {
}
```

`src/test/resources/fixtures/badpolicy_ignoreplus/module-info.java` → `module demo.badpolicy_ignoreplus { requires static org.lattejava.json; }`; `demo/Ig.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Ig(@JSONField(ignore = true, name = "x") String value) {
}
```

`src/test/java/org/lattejava/json/tests/processor/PolicyRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolicyRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void readOnlyWriteOnlyConflictRejected() throws Exception {
    assertFailsWith("badpolicy_rwconflict", "readOnly and writeOnly", "x");
  }

  @Test public void ignorePlusOtherRejected() throws Exception {
    assertFailsWith("badpolicy_ignoreplus", "ignore", "value");
  }
}
```

- [ ] **Step 10: Run the rejection test, then the full suite**

Run: `latte test --test=PolicyRejectionTest`
Expected: PASS — 2 tests green.

Run: `latte test`
Expected: PASS — 223 tests (219 + 2 codegen + 2 rejection), 0 failures. Existing types are unchanged (`serialize()`/`deserialize()` both true for non-`@JSONField` components).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/Component.java \
        src/main/jte/companion.jte src/main/jte/observerBody.jte \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/policies src/test/resources/fixtures/badpolicy_rwconflict \
        src/test/resources/fixtures/badpolicy_ignoreplus \
        src/test/java/org/lattejava/json/tests/processor/PolicyCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/PolicyRejectionTest.java
git commit -m "feat: @JSONField ignore/readOnly/writeOnly direction filtering

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `format` and `instant` codegen

Route formatted java.time components through a per-field `DateTimeFormatter`, and `instant != ISO` components through the JSON-integer path; validate both.

**Files:**
- Modify: `src/main/jte/companion.jte`, `src/main/jte/observerBody.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Modify: `src/test/resources/fixtures/policies/demo/` (add `Times.java`); `PolicyCodegenTest.java`; reject fixtures + `PolicyRejectionTest.java`

- [ ] **Step 1: Add the fixture**

`src/test/resources/fixtures/policies/demo/Times.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Times(
    @JSONField(format = "MM/dd/yyyy") LocalDate date,
    @JSONField(format = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime stamp,
    @JSONField(instant = InstantFormat.EPOCH_MILLIS) Instant millis,
    @JSONField(instant = InstantFormat.EPOCH_SECONDS) Instant seconds) {
}
```

- [ ] **Step 2: Write the failing tests**

Add to `src/test/java/org/lattejava/json/tests/processor/PolicyCodegenTest.java`:

```java
  @Test
  public void formatRoundTripsCustomPatterns() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> t = loader.loadClass("demo.Times");
      Class<?> j = loader.loadClass("demo.internal.TimesJSON");
      String json = "{\"date\":\"03/14/2026\",\"stamp\":\"2026-03-14T09:26:53\","
          + "\"millis\":1741944413000,\"seconds\":1741944413}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("date").invoke(o), java.time.LocalDate.of(2026, 3, 14));
      assertEquals(t.getMethod("millis").invoke(o), java.time.Instant.ofEpochMilli(1741944413000L));
      assertEquals(t.getMethod("seconds").invoke(o), java.time.Instant.ofEpochSecond(1741944413L));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void epochInstantsAreJSONIntegers() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> j = loader.loadClass("demo.internal.TimesJSON");
      Class<?> t = loader.loadClass("demo.Times");
      Object o = t.getConstructor(java.time.LocalDate.class, java.time.LocalDateTime.class,
              java.time.Instant.class, java.time.Instant.class)
          .newInstance(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0),
              java.time.Instant.ofEpochMilli(1000L), java.time.Instant.ofEpochSecond(2L));
      String json = (String) j.getMethod("toJSON", t).invoke(null, o);
      assertTrue(json.contains("\"millis\":1000"), "epoch millis must be a bare integer, got: " + json);
      assertTrue(json.contains("\"seconds\":2"), "epoch seconds must be a bare integer, got: " + json);
    }
  }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `latte test --test=PolicyCodegenTest`
Expected: FAIL — `format` components serialize via ISO `toString()` (wrong string) and epoch instants serialize as ISO strings, not integers.

- [ ] **Step 4: Emit formatter fields + route serialize in `companion.jte`**

In `src/main/jte/companion.jte`, **(a)** add formatter static fields after the instance field declarations. Find:

```jte
@for(Component c : view.components())
  private @template.declType(type = c.type()) ${c.name()};
@endfor
```

and insert, immediately after it:

```jte
@for(Component c : view.components())
@if(c.isFormatted())
  private static final DateTimeFormatter ${c.formatterField()} = DateTimeFormatter.ofPattern("${c.format()}")@if(c.formatNeedsZone()).withZone(ZoneOffset.UTC)@endif;
@endif
@endfor
```

**(b)** In `builder(...)`, add format/epoch branches as the first cases inside the `@if(c.serialize())` block:

```jte
@for(Component c : view.components())
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
```

(`DateTimeFormatter` and `ZoneOffset` are reachable via the companion's `import module java.base`. `.integer(..., Long)` exists as a boxed overload for the null-safe epoch expression.)

- [ ] **Step 5: Route deserialize in `observerBody.jte`**

In `src/main/jte/observerBody.jte`, replace the `string(...)` method with (formatted first, epoch-instants excluded from the string path):

```jte
  @Override public void string(String key, String value) {
    switch (key) {
@if(!view.discriminatorKey().isEmpty())
      case "${view.discriminatorKey()}" -> { /* discriminator: ignore */ }
@endif
@for(Component c : view.components())
@if(c.deserialize() && c.isFormatted())
      case "${c.wireKey()}" -> this.${c.name()} = ${c.formatterField()}.parse(value, ${c.formatType()}::from);
@elseif(c.deserialize() && c.type().isStringForm() && !c.isEpochInstant())
      case "${c.wireKey()}" -> this.${c.name()} = @template.fromString(type = c.type(), expr = "value");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
```

And replace the `integer(...)` method with (epoch-instants handled here):

```jte
  @Override public void integer(String key, long value) {
    switch (key) {
@for(Component c : view.components())
@if(c.deserialize() && c.isEpochInstant())
      case "${c.wireKey()}" -> this.${c.name()} = Instant.${c.epochFactory()}(value);
@elseif(c.deserialize() && c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "integer");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
```

- [ ] **Step 6: Run the codegen tests to verify they pass**

Run: `latte test --test=PolicyCodegenTest`
Expected: PASS — `formatRoundTripsCustomPatterns` and `epochInstantsAreJSONIntegers` green (plus the Task-2 direction tests).

- [ ] **Step 7: Add the format/instant validation**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, extend the policy block in `validateComponents` (added in Task 2). After the `ignore`+other check (and before the `continue`-less fallthrough to type validation), add:

```java
        String typeName = c.asType().toString();
        boolean formatType = typeName.equals("java.time.LocalDate") || typeName.equals("java.time.LocalDateTime")
            || typeName.equals("java.time.OffsetDateTime") || typeName.equals("java.time.ZonedDateTime")
            || typeName.equals("java.time.Instant");
        if (!policy.format().isEmpty()) {
          if (!formatType) {
            error(c, "@JSONField(format) on component [" + c.getSimpleName() + "] requires a LocalDate, LocalDateTime, "
                + "OffsetDateTime, ZonedDateTime, or Instant type, not [" + typeName + "]");
            ok = false;
            continue;
          }
          if (policy.format().indexOf('"') >= 0 || policy.format().indexOf('\\') >= 0) {
            error(c, "@JSONField(format) pattern [" + policy.format() + "] on component [" + c.getSimpleName()
                + "] contains a quote or backslash");
            ok = false;
            continue;
          }
          try {
            java.time.format.DateTimeFormatter.ofPattern(policy.format());
          } catch (IllegalArgumentException iae) {
            error(c, "@JSONField(format) pattern [" + policy.format() + "] on component [" + c.getSimpleName()
                + "] is not a valid DateTimeFormatter pattern: " + iae.getMessage());
            ok = false;
            continue;
          }
        }
        if (policy.instant() != InstantFormat.ISO) {
          if (!typeName.equals("java.time.Instant")) {
            error(c, "@JSONField(instant) on component [" + c.getSimpleName() + "] requires an Instant type, not ["
                + typeName + "]");
            ok = false;
            continue;
          }
          if (!policy.format().isEmpty()) {
            error(c, "@JSONField component [" + c.getSimpleName() + "] sets both instant and format (integer vs string)");
            ok = false;
            continue;
          }
        }
```

- [ ] **Step 8: Add the rejection fixtures + tests**

`src/test/resources/fixtures/badpolicy_formattype/` (`module demo.badpolicy_formattype { requires static org.lattejava.json; }` + `demo/F.java`):

```java
package demo;

import module org.lattejava.json;

@JSON
public record F(@JSONField(format = "MM/dd/yyyy") String notATime) {
}
```

`src/test/resources/fixtures/badpolicy_instanttype/` (module + `demo/I.java`):

```java
package demo;

import module org.lattejava.json;

@JSON
public record I(@JSONField(instant = InstantFormat.EPOCH_MILLIS) String notAnInstant) {
}
```

`src/test/resources/fixtures/badpolicy_instantformat/` (module + `demo/C.java`):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record C(@JSONField(instant = InstantFormat.EPOCH_MILLIS, format = "x") Instant both) {
}
```

Add to `PolicyRejectionTest`:

```java
  @Test public void formatOnNonTimeRejected() throws Exception {
    assertFailsWith("badpolicy_formattype", "format", "notATime");
  }

  @Test public void instantOnNonInstantRejected() throws Exception {
    assertFailsWith("badpolicy_instanttype", "instant", "notAnInstant");
  }

  @Test public void instantPlusFormatRejected() throws Exception {
    assertFailsWith("badpolicy_instantformat", "instant and format", "both");
  }
```

- [ ] **Step 9: Run the rejection tests, then the full suite**

Run: `latte test --test=PolicyRejectionTest`
Expected: PASS — 5 tests green.

Run: `latte test`
Expected: PASS — 228 tests (223 + 2 codegen + 3 rejection), 0 failures.

- [ ] **Step 10: Commit**

```bash
git add src/main/jte/companion.jte src/main/jte/observerBody.jte \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/policies src/test/resources/fixtures/badpolicy_formattype \
        src/test/resources/fixtures/badpolicy_instanttype src/test/resources/fixtures/badpolicy_instantformat \
        src/test/java/org/lattejava/json/tests/processor/PolicyCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/PolicyRejectionTest.java
git commit -m "feat: @JSONField format (DateTimeFormatter) and instant (epoch integer) codegen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full suite + composition spot-check**

Run: `latte test`
Expected: PASS — 228 tests, 0 failures. Confirm green: `PolicyCodegenTest`, `PolicyRejectionTest`, `AnnotationDeclarationTest`, plus the prior `NamingCodegenTest`, `TimeCodegenTest`, `SimpleRecordCodegenTest`, `NestedCodegenTest`, `PolyCodegenTest` (these prove the defaults are unchanged).

- [ ] **Step 2: Spot-check a generated companion**

Run: `find build/test/generated/policies -name 'TimesJSON.java' -exec cat {} \;`
Expected: a `private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");` field; `.string("date", … dateFormatter.format(value.date()))` and `.integer("millis", … value.millis().toEpochMilli())` in `builder`; `case "date" -> this.date = dateFormatter.parse(value, LocalDate::from);` in `string(...)`; `case "millis" -> this.millis = Instant.ofEpochMilli(value);` in `integer(...)`. And `DirectionsJSON` has no builder call or observer case for `writeOnly`/`ignored` as appropriate.

- [ ] **Step 3: No commit** (verification only). If any check fails, surface it to the reviewer rather than patching silently.

---

## Self-Review

**Spec coverage:**
- §"Dropped: required" → Task 1 (remove attribute, update test + doc).
- §1 model (`serialize()`/`deserialize()` + facts) → Task 2 (`Component`).
- §2 direction filtering → Task 2 (`companion.jte` `serialize()`, `observerBody.jte` `deserialize()`).
- §3 format (formatter fields + format/parse) → Task 3.
- §4 instant (`InstantFormat` + epoch path) → Task 1 (enum/attr) + Task 3 (codegen).
- §5 validation (readOnly+writeOnly, ignore+other, format type/pattern, instant type/conflict) → Task 2 + Task 3.
- §Testing → Task 2 (direction + 2 rejections), Task 3 (format/instant + 3 rejections).

**Placeholder scan:** none — every step is complete code or an exact before/after.

**Type consistency:** `InstantFormat{EPOCH_MILLIS,EPOCH_SECONDS,ISO}` (Task 1) used by `JSONField.instant()` (Task 1), `Component` facts (Task 2), and validation (Task 2/3). `Component.serialize()/deserialize()/isFormatted()/format()/formatterField()/formatType()/formatNeedsZone()/isEpochInstant()/epochMethod()/epochFactory()` (Task 2) used by `companion.jte`/`observerBody.jte` (Task 2/3). Validation reads `policy.readOnly()/writeOnly()/ignore()/name()/format()/instant()` directly. Test counts: 219 → 223 (T2) → 228 (T3).
