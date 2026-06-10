# Non-record Classes (@JSONConstructor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen for `@JSON` classes whose deserialization constructor is marked `@JSONConstructor` — members are the constructor's parameters, `finish()` calls that constructor, and serialize reads resolved public accessors (`getFoo()`/`isFoo()`/`foo()`/public field).

**Architecture:** An `@JSONConstructor` class is codegen-identical to a record on the deserialize side (members from the constructor's parameters; `finish()` calls the constructor), so the observer/`finish()` templates are unchanged. `Component` is generalized to read from any member element (record component **or** constructor parameter) and to carry a serialize read-accessor (`read()`). `@JSONField`/`@JSONCatchAll` work on parameters via a new `ElementType.PARAMETER` target. The processor branches member discovery + validation on `RECORD` vs `CLASS`.

**Tech Stack:** Java 25, JTE 3.2.1 templates, `javax.annotation.processing`, Latte build (`latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-08-nonrecord-classes-jsonconstructor-design.md`

---

## Notes (read first)

- **Members = constructor parameters** for an `@JSONConstructor` class (name/type/order). The deserialize observer + `finish()` (`new <Class>(this.p1, …)`) are the existing record templates, **unchanged**.
- **`read()`** is the new serialize-accessor suffix: records → `name + "()"` (so records are byte-identical); classes → resolved `getFoo()`/`isFoo()`/`foo()`/`foo` (public only).
- **`@JSONField`/`@JSONCatchAll` on a parameter** work because a `VariableElement` exposes `getSimpleName()`/`asType()`/`getAnnotation(...)` just like a `RecordComponentElement` — once `PARAMETER` is in `@Target`.
- **A reader is needed only for a serialized member** (`serialize()`); a `writeOnly`/`ignore` parameter needs none.

---

## File Structure

**Create:**
- `src/test/resources/fixtures/classes/` + reject fixtures.
- `src/test/java/org/lattejava/json/tests/processor/ClassCodegenTest.java`, `ClassRejectionTest.java`.

**Modify:**
- `src/main/java/org/lattejava/json/JSONField.java`, `JSONCatchAll.java` — add `ElementType.PARAMETER`.
- `src/main/java/org/lattejava/json/JSONConstructor.java` — drop the "TODO".
- `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java` — assert the `PARAMETER` target.
- `src/main/java/org/lattejava/json/jte/Component.java` — `read()`; generalize to an `Element`.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — `catchAllRead()`.
- `src/main/jte/companion.jte` — `builder(...)` value reads use `c.read()`; spread uses `catchAllRead()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — admit `CLASS`; class member discovery + accessor resolution + validation.

**Acceptance gate every task:** full suite green — `latte test` (currently 242).

---

## Task 1: Annotation `@Target` additions

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONField.java`, `JSONCatchAll.java`, `JSONConstructor.java`
- Modify: `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`

- [ ] **Step 1: Write the failing test**

In `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`, add two tests:

```java
  @Test
  public void jsonFieldTargetsParameter() {
    var target = JSONField.class.getAnnotation(Target.class);
    assertTrue(java.util.Arrays.asList(target.value()).contains(ElementType.PARAMETER),
        "@JSONField must target PARAMETER");
  }

  @Test
  public void jsonCatchAllTargetsParameter() {
    var target = JSONCatchAll.class.getAnnotation(Target.class);
    assertTrue(java.util.Arrays.asList(target.value()).contains(ElementType.PARAMETER),
        "@JSONCatchAll must target PARAMETER");
  }
```

(`Target`/`ElementType` resolve via the existing `import module java.base;`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=AnnotationDeclarationTest`
Expected: FAIL — `PARAMETER` is not in either `@Target`.

- [ ] **Step 3: Add `PARAMETER` to `@JSONField`**

In `src/main/java/org/lattejava/json/JSONField.java`, change the `@Target`:

```java
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
```

- [ ] **Step 4: Add `PARAMETER` to `@JSONCatchAll`**

In `src/main/java/org/lattejava/json/JSONCatchAll.java`, change the `@Target`:

```java
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
```

- [ ] **Step 5: Drop the TODO on `@JSONConstructor`**

In `src/main/java/org/lattejava/json/JSONConstructor.java`, remove the `<p>` + `TODO: Not implemented yet.` lines from the Javadoc (leave the descriptive sentence).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `latte test --test=AnnotationDeclarationTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `latte test`
Expected: PASS — 244 tests (242 + 2), 0 failures. Adding `PARAMETER` is additive — records read `@JSONField`/`@JSONCatchAll` from the `RECORD_COMPONENT` target, unaffected.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONField.java src/main/java/org/lattejava/json/JSONCatchAll.java \
        src/main/java/org/lattejava/json/JSONConstructor.java \
        src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java
git commit -m "feat: target PARAMETER for @JSONField/@JSONCatchAll (constructor-param config)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Generalize `Component` with `read()` (records byte-identical)

A behavior-preserving refactor: `Component` reads from any `Element` and carries a serialize read-accessor. Records get `read() == name()`, so generated record companions are unchanged.

**Files:**
- Modify: `src/main/java/org/lattejava/json/jte/Component.java`, `CompanionView.java`
- Modify: `src/main/jte/companion.jte`

- [ ] **Step 1: Generalize `Component`**

Replace `src/main/java/org/lattejava/json/jte/Component.java` with:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

import org.lattejava.json.InstantFormat;
import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONField;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;

/**
 * Template-facing view of one {@code @JSON} member — a record component or an {@code @JSONConstructor} parameter: its
 * Java name, its wire key, the serialize read-accessor ({@link #read()}), the {@link TypeView} facts, and its
 * {@code @JSONField}/{@code @JSONCatchAll} facts. All serializer/observer code is assembled from these facts in the JTE
 * templates — there is no code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final boolean catchAll;
  private final String format;
  private final boolean ignore;
  private final InstantFormat instant;
  private final String name;
  private final String read;
  private final boolean readOnly;
  private final TypeView type;
  private final String wireKey;
  private final boolean writeOnly;

  /** A record component: the serialize read-accessor is the bare {@code name()} accessor. */
  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element, NamingStrategy naming) {
    this(processingEnv, element, naming, element.getSimpleName() + "()");
  }

  /**
   * A general member (a record component or a constructor parameter) with an explicit serialize read-accessor suffix
   * (e.g. {@code "getFoo()"}, {@code "foo"}).
   */
  public Component(ProcessingEnvironment processingEnv, Element element, NamingStrategy naming, String read) {
    JSONField field = element.getAnnotation(JSONField.class);
    this.catchAll = element.getAnnotation(JSONCatchAll.class) != null;
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
    this.wireKey = wireKey(element, naming);
    this.read = read;
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
  public static String wireKey(Element element, NamingStrategy naming) {
    JSONField field = element.getAnnotation(JSONField.class);
    String override = field == null ? "" : field.name();
    return override.isEmpty() ? NamingStrategies.apply(naming, element.getSimpleName().toString()) : override;
  }

  /** Whether this member is deserialized (appears in the observer): not ignored and not read-only. */
  public boolean deserialize() {
    return !ignore && !readOnly;
  }

  /** The {@code Instant.ofEpoch*} factory for an epoch-instant member (deserialize). */
  public String epochFactory() {
    return instant == InstantFormat.EPOCH_MILLIS ? "ofEpochMilli" : "ofEpochSecond";
  }

  /** The {@code Instant} accessor (e.g. {@code toEpochMilli}) for an epoch-instant member (serialize). */
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

  /** The generated static formatter field name for a formatted member. */
  public String formatterField() {
    return name + "Formatter";
  }

  public boolean isCatchAll() {
    return catchAll;
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

  /** The serialize read-accessor suffix, such that {@code value.<read()>} reads the member (e.g. {@code getFoo()}). */
  public String read() {
    return read;
  }

  /** Whether this member is serialized (appears in {@code toJSON}): not ignored and not write-only. */
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

- [ ] **Step 2: Add `catchAllRead()` to `CompanionView`**

In `src/main/java/org/lattejava/json/jte/CompanionView.java`, add (alphabetical, right after `catchAll()`):

```java
  /** The serialize read-accessor of the catch-all member (e.g. {@code extras()}/{@code getExtras()}), or {@code ""}. */
  public String catchAllRead() {
    return components.stream().filter(Component::isCatchAll).findFirst().map(Component::read).orElse("");
  }
```

- [ ] **Step 3: Use `read()` for value reads in `companion.jte`**

In `src/main/jte/companion.jte`, in `builder(...)`, every member **value read** `value.${c.name()}()` becomes `value.${c.read()}`, and the catch-all spread uses `catchAllRead()`. Replace the whole `builder(...)` method with:

```jte
  private static JSONBuilder builder(${view.simpleName()} value) {
    JSONBuilder b = new JSONBuilder(${view.omitNulls()})
@if(!view.discriminatorKey().isEmpty())
        .string("${view.discriminatorKey()}", "${view.discriminatorValue()}")
@endif
@for(Component c : view.typedComponents())
@if(c.serialize())
@if(c.isFormatted())
        .string("${c.wireKey()}", value.${c.read()} == null ? null : ${c.formatterField()}.format(value.${c.read()}))
@elseif(c.isEpochInstant())
        .integer("${c.wireKey()}", value.${c.read()} == null ? null : value.${c.read()}.${c.epochMethod()}())
@elseif(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.read()} == null ? null : ${c.name()}ToJSON(value.${c.read()}))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.read()} == null ? null : ${c.name()}ToJSON(value.${c.read()}))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.wireKey() + "\"", val = "value." + c.read())
@endif
@endif
@endfor
        ;
@if(!view.catchAll().isEmpty())
    for (var entry : value.${view.catchAllRead()}.entrySet()) {
      b.any(entry.getKey(), entry.getValue());
    }
@endif
    return b;
  }
```

(For a record member, `read()` is `name()`, so `value.${c.read()}` is `value.name()` and `catchAllRead()` is `extras()` — byte-identical to today.)

- [ ] **Step 4: Run the full suite (no new test — behavior-preserving refactor)**

Run: `latte test`
Expected: PASS — 244 tests, 0 failures. Records/classes-of-records are unchanged.

- [ ] **Step 5: Spot-check a generated companion is unchanged**

Run: `find build/test/generated/simple -name 'UserJSON.java' -exec sed -n '/private static JSONBuilder builder/,/return b;/p' {} \;`
Expected: `.string("name", value.name())`, `.integer("age", value.age())`, etc. — bare `name()` accessors, identical to before this task.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/Component.java src/main/java/org/lattejava/json/jte/CompanionView.java \
        src/main/jte/companion.jte
git commit -m "refactor: Component carries a serialize read-accessor (read()); records unchanged

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Class recognition, member discovery, accessor resolution, validation

Make a `@JSON` class with one `@JSONConstructor` round-trip; reject the malformed cases.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Create: `src/test/resources/fixtures/classes/` + `ClassCodegenTest.java`; reject fixtures + `ClassRejectionTest.java`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/classes/module-info.java`:

```java
module demo.classes {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/classes/demo/Point.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public class Point {
  private final int x;
  private final int y;

  @JSONConstructor
  public Point(int x, int y) {
    this.x = x;
    this.y = y;
  }

  public int getX() {
    return x;
  }

  public int getY() {
    return y;
  }
}
```

`src/test/resources/fixtures/classes/demo/Mixed.java` (exercises every accessor kind):

```java
package demo;

import module org.lattejava.json;

@JSON
public class Mixed {
  private final String name;
  private final boolean active;
  private final int count;
  public final String tag;

  @JSONConstructor
  public Mixed(String name, boolean active, int count, String tag) {
    this.name = name;
    this.active = active;
    this.count = count;
    this.tag = tag;
  }

  public String getName() {
    return name;
  }

  public boolean isActive() {
    return active;
  }

  public int count() {
    return count;
  }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/ClassCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ClassCodegenTest {
  static ProcessorHarness.Result classes;

  @BeforeClass
  public void compileOnce() throws Exception {
    classes = ProcessorHarness.compile("classes");
    assertTrue(classes.success(), classes.diagnostics().toString());
  }

  @Test
  public void pointRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Point");
      Class<?> j = loader.loadClass("demo.internal.PointJSON");
      String json = "{\"x\":1,\"y\":2}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getX").invoke(o), 1);
      assertEquals(t.getMethod("getY").invoke(o), 2);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void mixedAccessorsResolve() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Mixed");
      Class<?> j = loader.loadClass("demo.internal.MixedJSON");
      String json = "{\"name\":\"a\",\"active\":true,\"count\":3,\"tag\":\"t\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      // serialize reads via getName()/isActive()/count()/public field tag
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=ClassCodegenTest`
Expected: FAIL — `@BeforeClass` compile fails: the processor rejects `Point` ("@JSON supports only records and sealed @JSONTypeInfo interfaces").

- [ ] **Step 4: Admit `CLASS` and reject `@JSONConstructor` on a record in `process()`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, replace the guard + dispatch in `process()` (the block from `if (e.getKind() != ElementKind.RECORD && !polyParent)` through `generateCompanion(type, module);`) with:

```java
      if (e.getKind() != ElementKind.RECORD && e.getKind() != ElementKind.CLASS && !polyParent) {
        error(e, "@JSON supports records, classes, and sealed @JSONTypeInfo interfaces; ["
            + qualified(e) + "] is a [" + e.getKind() + "]");
        continue;
      }

      ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
      if (module == null || module.isUnnamed()) {
        error(e, "@JSON requires a named module (module-info.java); type [" + type.getQualifiedName() + "] is in the unnamed module");
        continue;
      }

      if (polyParent) {
        if (!validatePolymorphic(type)) {
          continue;
        }
        generatePolymorphic(type, module);
        continue;
      }

      if (e.getKind() == ElementKind.RECORD && !jsonConstructors(type).isEmpty()) {
        error(type, "@JSONConstructor on record [" + type.getQualifiedName()
            + "] is redundant; records use their canonical constructor");
        continue;
      }

      boolean valid = e.getKind() == ElementKind.CLASS ? validateClass(type) : validateComponents(type);
      if (!valid) {
        continue;
      }

      generateCompanion(type, module);
```

- [ ] **Step 5: Branch member discovery in `generateCompanion`**

In `generateCompanion`, replace the component-building loop (the `for (RecordComponentElement c : record.getRecordComponents())` block) with a kind branch. Replace:

```java
    NamingStrategy naming = readNaming(record);
    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    for (RecordComponentElement c : record.getRecordComponents()) {
      components.add(new Component(processingEnv, c, naming));
      collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
    }
```

with:

```java
    NamingStrategy naming = readNaming(record);
    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    if (record.getKind() == ElementKind.CLASS) {
      for (VariableElement p : jsonConstructors(record).get(0).getParameters()) {
        components.add(new Component(processingEnv, p, naming, resolveRead(record, p)));
        collectEnums(new TypeView(processingEnv, p.asType()), enumImports);
      }
    } else {
      for (RecordComponentElement c : record.getRecordComponents()) {
        components.add(new Component(processingEnv, c, naming));
        collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
      }
    }
```

(The parameter is named `record` but now holds a class too; everything below — discriminator scan, `CompanionView`, rendering — is unchanged and works for a class. `finish()` emits `new <SimpleName>(this.p1, …)`, which is the `@JSONConstructor`.)

- [ ] **Step 6: Add the `jsonConstructors` and `resolveRead` helpers**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, add (alphabetical among the private methods):

```java
  private List<ExecutableElement> jsonConstructors(TypeElement type) {
    return javax.lang.model.util.ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
        .filter(c -> c.getAnnotation(JSONConstructor.class) != null)
        .toList();
  }

  /**
   * The serialize read-accessor suffix for a class member named after constructor parameter {@code param}: the first
   * public match of {@code getFoo()}, {@code isFoo()} (boolean only), {@code foo()}, or public field {@code foo}; or
   * {@code ""} when none (the member must then be write-only/ignored).
   */
  private String resolveRead(TypeElement clazz, VariableElement param) {
    String name = param.getSimpleName().toString();
    String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    String typeName = param.asType().toString();
    boolean booleanType = typeName.equals("boolean") || typeName.equals("java.lang.Boolean");
    boolean getter = false;
    boolean isGetter = false;
    boolean bare = false;
    boolean field = false;
    for (Element m : processingEnv.getElementUtils().getAllMembers(clazz)) {
      if (!m.getModifiers().contains(Modifier.PUBLIC)) {
        continue;
      }
      if (m.getKind() == ElementKind.METHOD && ((ExecutableElement) m).getParameters().isEmpty()) {
        String mn = m.getSimpleName().toString();
        if (mn.equals("get" + cap)) {
          getter = true;
        } else if (booleanType && mn.equals("is" + cap)) {
          isGetter = true;
        } else if (mn.equals(name)) {
          bare = true;
        }
      } else if (m.getKind() == ElementKind.FIELD && m.getSimpleName().toString().equals(name)) {
        field = true;
      }
    }
    if (getter) {
      return "get" + cap + "()";
    }
    if (isGetter) {
      return "is" + cap + "()";
    }
    if (bare) {
      return name + "()";
    }
    if (field) {
      return name;
    }
    return "";
  }
```

- [ ] **Step 7: Generalize `validateComponents` to members + add `validateClass`**

In `validateComponents`, change the iteration to accept any member element. Replace the method signature + the loop header:

```java
  private boolean validateComponents(TypeElement record) {
    boolean ok = true;
    NamingStrategy naming = readNaming(record);
    Map<String, String> wireKeys = new HashMap<>();
    int catchAllCount = 0;
    for (RecordComponentElement c : record.getRecordComponents()) {
```

with:

```java
  private boolean validateComponents(TypeElement record) {
    return validateMembers(record, record.getRecordComponents());
  }

  private boolean validateMembers(TypeElement type, List<? extends Element> members) {
    boolean ok = true;
    NamingStrategy naming = readNaming(type);
    Map<String, String> wireKeys = new HashMap<>();
    int catchAllCount = 0;
    for (Element c : members) {
```

In that loop body, every use of `record` becomes `type`, and `c` is now an `Element` (its `getSimpleName()`/`asType()`/`getAnnotation()` are all on `Element`, so no other change is needed). Also change the trailing `catchAllCount > 1` error and `return ok;` to use `type` (the wording `type [" + type.getQualifiedName()`).

Then add `validateClass` (alphabetical, before `validateComponents`):

```java
  private boolean validateClass(TypeElement type) {
    List<ExecutableElement> ctors = jsonConstructors(type);
    if (ctors.isEmpty()) {
      error(type, "@JSON class [" + type.getQualifiedName()
          + "] requires a constructor annotated @JSONConstructor (no-arg + setters not yet supported)");
      return false;
    }
    if (ctors.size() > 1) {
      error(type, "@JSON class [" + type.getQualifiedName()
          + "] has [" + ctors.size() + "] @JSONConstructor constructors; exactly one is allowed");
      return false;
    }
    List<? extends VariableElement> params = ctors.get(0).getParameters();
    boolean ok = validateMembers(type, params);
    for (VariableElement p : params) {
      JSONField pf = p.getAnnotation(JSONField.class);
      boolean serialized = pf == null || (!pf.ignore() && !pf.writeOnly());
      if (serialized && p.getAnnotation(JSONCatchAll.class) == null && resolveRead(type, p).isEmpty()) {
        error(p, "no usable reader for member [" + p.getSimpleName() + "] on [" + type.getQualifiedName()
            + "]; add a getFoo()/isFoo()/foo()/public field, or mark the parameter @JSONField(writeOnly = true)");
        ok = false;
      }
    }
    return ok;
  }
```

(`ExecutableElement`/`VariableElement`/`Modifier`/`Element` resolve via `import module java.compiler`; `List`/`Map`/`HashMap` via `import module java.base`.)

- [ ] **Step 8: Run the codegen test to verify it passes**

Run: `latte test --test=ClassCodegenTest`
Expected: PASS — `pointRoundTrips` and `mixedAccessorsResolve` green.

- [ ] **Step 9: Write the rejection fixtures + test**

`src/test/resources/fixtures/badclass_noctor/module-info.java` → `module demo.badclass_noctor { requires static org.lattejava.json; }`; `demo/NoCtor.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public class NoCtor {
  public int x;
  public NoCtor() {}
}
```

`src/test/resources/fixtures/badclass_twoctor/` + `demo/TwoCtor.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public class TwoCtor {
  private final int x;
  @JSONConstructor public TwoCtor(int x) { this.x = x; }
  @JSONConstructor public TwoCtor(int x, int y) { this.x = x; }
  public int getX() { return x; }
}
```

`src/test/resources/fixtures/badclass_noreader/` + `demo/NoReader.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public class NoReader {
  private final String secret;
  @JSONConstructor public NoReader(String secret) { this.secret = secret; }
}
```

`src/test/resources/fixtures/badrecord_jsonctor/` + `demo/Bad.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Bad(int x) {
  @JSONConstructor public Bad { }
}
```

(Each fixture's `module-info.java` is the one-liner with the matching module name.)

`src/test/java/org/lattejava/json/tests/processor/ClassRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ClassRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void classWithoutConstructorRejected() throws Exception {
    assertFailsWith("badclass_noctor", "requires a constructor annotated @JSONConstructor", "NoCtor");
  }

  @Test public void twoConstructorsRejected() throws Exception {
    assertFailsWith("badclass_twoctor", "exactly one is allowed", "TwoCtor");
  }

  @Test public void noReaderRejected() throws Exception {
    assertFailsWith("badclass_noreader", "no usable reader", "[secret]");
  }

  @Test public void constructorOnRecordRejected() throws Exception {
    assertFailsWith("badrecord_jsonctor", "@JSONConstructor on record", "redundant");
  }
}
```

- [ ] **Step 10: Run the rejection test, then the full suite**

Run: `latte test --test=ClassRejectionTest`
Expected: PASS — 4 tests green.

Run: `latte test`
Expected: PASS — 250 tests (244 + 2 codegen + 4 rejection), 0 failures. Records are untouched (`validateComponents` delegates to `validateMembers` over the record components; the kind branch in `generateCompanion` takes the record path).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/classes src/test/resources/fixtures/badclass_noctor \
        src/test/resources/fixtures/badclass_twoctor src/test/resources/fixtures/badclass_noreader \
        src/test/resources/fixtures/badrecord_jsonctor \
        src/test/java/org/lattejava/json/tests/processor/ClassCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/ClassRejectionTest.java
git commit -m "feat: @JSON classes via @JSONConstructor (members, accessor resolution, validation)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Composition + final verification

Prove the `@JSONField`/`@JSONCatchAll`/naming/nesting machinery composes for `@JSONConstructor` classes via parameter annotations.

**Files:**
- Create: `.../fixtures/classes/demo/` additions; `ClassCodegenTest.java` additions

- [ ] **Step 1: Add the composition fixtures**

`src/test/resources/fixtures/classes/demo/Configured.java` (param `@JSONField` + write-only + naming):

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public class Configured {
  private final String userName;
  private final String secret;

  @JSONConstructor
  public Configured(String userName, @JSONField(writeOnly = true) String secret) {
    this.userName = userName;
    this.secret = secret;
  }

  public String getUserName() {
    return userName;
  }
}
```

`src/test/resources/fixtures/classes/demo/Caught.java` (param `@JSONCatchAll`):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Caught {
  private final String id;
  private final Map<String, Object> extras;

  @JSONConstructor
  public Caught(String id, @JSONCatchAll Map<String, Object> extras) {
    this.id = id;
    this.extras = extras;
  }

  public String getId() {
    return id;
  }

  public Map<String, Object> getExtras() {
    return extras;
  }
}
```

`src/test/resources/fixtures/classes/demo/Household.java` (a record nesting an `@JSONConstructor` class):

```java
package demo;

import module org.lattejava.json;

@JSON
public record Household(String name, Point origin) {
}
```

- [ ] **Step 2: Write the failing tests**

Add to `ClassCodegenTest`:

```java
  @Test
  public void namingAndWriteOnlyOnParameters() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Configured");
      Class<?> j = loader.loadClass("demo.internal.ConfiguredJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"user_name\":\"a\",\"secret\":\"s\"}");
      assertEquals(t.getMethod("getUserName").invoke(o), "a");
      // snake_cased key; secret is write-only (no reader) so it's omitted on serialize
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"user_name\":\"a\"}");
    }
  }

  @Test
  public void catchAllOnParameter() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Caught");
      Class<?> j = loader.loadClass("demo.internal.CaughtJSON");
      String json = "{\"id\":\"a\",\"x\":42,\"y\":true}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var extras = (java.util.Map<?, ?>) t.getMethod("getExtras").invoke(o);
      assertEquals(extras.get("x"), 42L);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void classNestedInRecord() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Household");
      Class<?> j = loader.loadClass("demo.internal.HouseholdJSON");
      String json = "{\"name\":\"h\",\"origin\":{\"x\":1,\"y\":2}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the tests to verify they fail, then pass**

Run: `latte test --test=ClassCodegenTest`
Expected: FAIL first only if a gap exists. These exercise existing machinery (naming, `@JSONField(writeOnly)`, `@JSONCatchAll`, nested `@JSON`) through parameter members — they should pass with no further production changes, since Tasks 1–3 wired parameters through `Component`/validation. If `catchAllOnParameter` or `classNestedInRecord` fails, the gap is in the catch-all `catchAllRead()` (Task 2) or the nested-type detection treating a class as `@JSON` — investigate and report rather than altering the test.

If all three pass directly, that confirms the composition is free; proceed.

- [ ] **Step 4: Run the full suite**

Run: `latte test`
Expected: PASS — 253 tests (250 + 3), 0 failures.

- [ ] **Step 5: Spot-check generated class companions**

Run: `find build/test/generated/classes -name 'PointJSON.java' -o -name 'CaughtJSON.java' | xargs -I{} sh -c 'echo "== {} =="; cat {}'`
Expected: `PointJSON.builder` reads `value.getX()`/`value.getY()`; `finish()` is `new Point(this.x, this.y)`; `CaughtJSON` pre-inits the `extras` map, captures unknowns in the `default` arms, and spreads via `value.getExtras()`; `ConfiguredJSON.builder` omits `secret` (write-only) and uses snake_case keys.

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/fixtures/classes src/test/java/org/lattejava/json/tests/processor/ClassCodegenTest.java
git commit -m "test: @JSONField/@JSONCatchAll/naming/nesting compose for @JSONConstructor classes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §1 recognition (admit `CLASS`) → Task 3 Step 4.
- §2 members from `@JSONConstructor` params (+ `@JSONField`/`@JSONCatchAll` off the param) → Task 3 Step 5 + Task 1 (`PARAMETER` targets); proven in Task 4.
- §3 accessor resolution (`getFoo`/`isFoo`/`foo()`/field; write-only carve-out) → Task 3 Step 6 (`resolveRead`) + Step 7 (`validateClass` reader check).
- §4 `read()` + builder value-reads → Task 2.
- §5 validation (missing/duplicate `@JSONConstructor`, `@JSONConstructor` on record, no-reader, unsupported type, existing `@JSONField`/catch-all rejections) → Task 3 Steps 4/7.
- §6 files, §7 conventions → across tasks.
- §Testing → Task 3 (round-trip, accessors, rejections), Task 4 (param config, write-only, catch-all, naming, nesting).

**Placeholder scan:** none — every step is complete code or an exact before/after.

**Type consistency:** `Component(Element, naming, read)` + `read()` (Task 2) used by `companion.jte` (Task 2) and built from `VariableElement` params (Task 3 Step 5). `Component.wireKey(Element, …)` generalization (Task 2) consumed by `validateMembers` (Task 3 Step 7). `jsonConstructors`/`resolveRead` (Task 3 Step 6) used by `process()`/`generateCompanion`/`validateClass` (Steps 4/5/7). `validateMembers(TypeElement, List<? extends Element>)` (Step 7) called by `validateComponents` and `validateClass`. `CompanionView.catchAllRead()` (Task 2) used by `companion.jte` spread. Test counts: 242 → 244 (T1) → 244 (T2) → 250 (T3) → 253 (T4).
