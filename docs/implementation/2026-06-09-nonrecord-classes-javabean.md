# Non-record Classes (JavaBean / no-arg + setter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen for `@JSON` classes **without** `@JSONConstructor` — JavaBeans whose members are properties (getter/setter accessors + public/annotated fields), constructed via a public no-arg constructor and populated via setters / public fields.

**Architecture:** A bean is record-identical on the observer (the observer body, `defaultArm`, field decls, serialize `builder(...)` are unchanged); only `finish()` branches to `T value = new T(); value.setFoo(this.foo); … return value;`. Members are discovered as **properties** (prefixed `getFoo`/`isFoo`/`setFoo` + public/annotated fields, inherited base-first, `transient`/`static` skipped). `Component` gains a write accessor (`write()`/`writeIsSetter()`) and a property constructor; read-only/write-only fall out of which accessors exist, via new `hasReader`/`hasWriter` gates on `serialize()`/`deserialize()`. `@JSONField`/`@JSONCatchAll` resolve field-first-then-accessor (new `ElementType.METHOD` target).

**Tech Stack:** Java 25, JTE 3.2.1 templates, `javax.annotation.processing`, Latte build (`latte clean && latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-09-nonrecord-classes-javabean-design.md`

---

## Notes (read first)

- **A class *with* `@JSONConstructor` is unchanged (Cycle 1).** This cycle is strictly the no-`@JSONConstructor` class. `boolean bean = type.getKind() == CLASS && jsonConstructors(type).isEmpty()` is the switch.
- **Property discovery uses two sources:** the **superclass-chain field walk** (`getEnclosedElements`, includes inherited *private* fields — for public/annotated-field discovery, `transient` detection, and field-first config) and **`getAllMembers`** (inherited public getters/setters/fields — for accessor resolution). `java.lang.Object` is excluded by the chain walk (it stops before `Object`), so `getClass()` never becomes a property.
- **Accessors are prefixed only** — `getFoo()`/`isFoo()` getters, `setFoo(T)` setters. No bare `foo()` (a bean can't tell a bare accessor from an ordinary method).
- **`finish()` is the only template change.** Everything else is processor + `Component`/`CompanionView`.
- **Run `latte clean` before every `latte test`** — this cycle changes `JSONProcessor`/`Component` internal APIs and Latte's incremental compile leaves stale descriptors (`NoSuchMethodError`) otherwise.

---

## File Structure

**Create:**
- `src/test/resources/fixtures/beans/` + reject fixtures.
- `src/test/java/org/lattejava/json/tests/processor/BeanCodegenTest.java`, `BeanRejectionTest.java`.

**Modify:**
- `src/main/java/org/lattejava/json/JSONField.java`, `JSONCatchAll.java` — add `ElementType.METHOD`.
- `src/main/java/org/lattejava/json/jte/Component.java` — `write()`/`writeIsSetter()`; `hasReader`/`hasWriter` gates; property constructor.
- `src/main/java/org/lattejava/json/jte/CompanionView.java` — `beanConstructed()`.
- `src/main/jte/observerBody.jte` — `finish()` branches on `beanConstructed()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — bean discovery, `validateBean`, factored `validateType`/`validatePolicy`, the bean branches in `generateCompanion`/`validateClass`/`process`.

**Acceptance gate every task:** full suite green — `latte clean && latte test` (currently 254).

---

## Task 1: `@Target` METHOD additions

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONField.java`, `JSONCatchAll.java`
- Modify: `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`

- [ ] **Step 1: Write the failing tests**

In `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`, add:

```java
  @Test
  public void jsonFieldTargetsMethod() {
    var target = JSONField.class.getAnnotation(Target.class);
    assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD), "@JSONField must target METHOD");
  }

  @Test
  public void jsonCatchAllTargetsMethod() {
    var target = JSONCatchAll.class.getAnnotation(Target.class);
    assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD), "@JSONCatchAll must target METHOD");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=AnnotationDeclarationTest`
Expected: FAIL — `METHOD` not in either `@Target`.

- [ ] **Step 3: Add `METHOD` to both `@Target`s**

In `JSONField.java` and `JSONCatchAll.java`, change the `@Target` to (alphabetical):

```java
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `latte test --test=AnnotationDeclarationTest` → PASS.
Run: `latte clean && latte test` → PASS, **256 tests** (254 + 2), 0 failures. Additive — records/params/fields read from their own targets.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONField.java src/main/java/org/lattejava/json/JSONCatchAll.java \
        src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java
git commit -m "feat: target METHOD for @JSONField/@JSONCatchAll (bean accessor config)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `Component` write facts + accessor gating; `CompanionView.beanConstructed()`

Groundwork: add the write accessor + `hasReader`/`hasWriter` gating + a property constructor. Behavior-preserving (records/`@JSONConstructor` keep `hasReader=!read.isEmpty()`, `hasWriter=true`, so `serialize()`/`deserialize()` are unchanged).

**Files:**
- Modify: `src/main/java/org/lattejava/json/jte/Component.java`, `CompanionView.java`

- [ ] **Step 1: Add fields, gating, and the property constructor to `Component`**

In `src/main/java/org/lattejava/json/jte/Component.java`:

Add `import module java.compiler;` already present. Add four fields (alphabetical among the existing finals):

```java
  private final boolean hasReader;
  private final boolean hasWriter;
```
```java
  private final String write;
  private final boolean writeSetter;
```

In the existing 4-arg general constructor, set the new fields (records/parameters have no per-member writer — `finish()` uses the constructor — so `hasWriter` is true and `write` is empty):

```java
    this.hasReader = !read.isEmpty();
    this.hasWriter = true;
    this.write = "";
    this.writeSetter = false;
```

Add the property constructor (after the 4-arg constructor):

```java
  /**
   * A JavaBean property. The {@code @JSONField}/{@code @JSONCatchAll} facts come from {@code config} (the field or an
   * accessor, resolved field-first by the processor); the wire key, type, and read/write accessors are passed
   * explicitly. {@code read}/{@code write} are empty when the property has no getter/setter+field — folded into
   * {@code hasReader}/{@code hasWriter} so a getter-only property is read-only and a setter-only property write-only.
   */
  public Component(ProcessingEnvironment processingEnv, String name, TypeMirror type, Element config,
                   NamingStrategy naming, String read, String write, boolean writeSetter) {
    JSONField field = config == null ? null : config.getAnnotation(JSONField.class);
    String override = field == null ? "" : field.name();
    this.catchAll = config != null && config.getAnnotation(JSONCatchAll.class) != null;
    this.name = name;
    this.type = new TypeView(processingEnv, type);
    this.wireKey = override.isEmpty() ? NamingStrategies.apply(naming, name) : override;
    this.read = read;
    this.write = write;
    this.writeSetter = writeSetter;
    this.hasReader = !read.isEmpty();
    this.hasWriter = !write.isEmpty();
    this.ignore = field != null && field.ignore();
    this.readOnly = field != null && field.readOnly();
    this.writeOnly = field != null && field.writeOnly();
    this.format = field == null ? "" : field.format();
    this.instant = field == null ? InstantFormat.ISO : field.instant();
  }
```

Update `serialize()` and `deserialize()` to gate on accessor presence:

```java
  /** Whether this member is deserialized (appears in the observer): not ignored, not read-only, and writable. */
  public boolean deserialize() {
    return !ignore && !readOnly && hasWriter;
  }
```
```java
  /** Whether this member is serialized (appears in {@code toJSON}): not ignored, not write-only, and readable. */
  public boolean serialize() {
    return !ignore && !writeOnly && hasReader;
  }
```

Add the two write accessors (alphabetical — `write()`/`writeIsSetter()` after `wireKey()`):

```java
  /** The deserialize write target's Java name — a setter (e.g. {@code setFoo}) or a public field name. */
  public String write() {
    return write;
  }

  /** Whether {@link #write()} is a setter method (vs. a public field). */
  public boolean writeIsSetter() {
    return writeSetter;
  }
```

- [ ] **Step 2: Add `beanConstructed()` to `CompanionView`**

In `src/main/java/org/lattejava/json/jte/CompanionView.java`, add a `beanConstructed` field + constructor parameter (last parameter) + accessor.

Field (alphabetical, after `omitNulls`):

```java
  private final boolean beanConstructed;
```

Wait — fields are alphabetical by name; place `beanConstructed` first (it sorts before `companionName`). Insert it as the first field:

```java
  private final boolean beanConstructed;
  private final String companionName;
```

Constructor — add `boolean beanConstructed` as the final parameter and assign it:

```java
  public CompanionView(String companionPackage, String internalPackage, String qualifiedType, String simpleName,
                       String companionName, boolean omitNulls, boolean strict, List<String> enumImports,
                       List<Component> components, String discriminatorKey, String discriminatorValue,
                       boolean beanConstructed) {
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
    this.beanConstructed = beanConstructed;
  }
```

Accessor (alphabetical — first method, before `catchAll()`):

```java
  /** Whether {@code finish()} constructs via a no-arg constructor + setters/fields (a JavaBean) rather than a constructor call. */
  public boolean beanConstructed() {
    return beanConstructed;
  }
```

- [ ] **Step 3: Pass `false` from the existing `generateCompanion` call site**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, the `new CompanionView(...)` call (around line 170) — add `, false` as the final argument (Task 3 makes it conditional):

```java
    CompanionView view = new CompanionView(companionPkg, internalPkg, qualifiedType, simpleName, companion,
        readOmitNulls(type), readStrict(type), List.copyOf(enumImports), components,
        discriminatorKey, discriminatorValue, false);
```

- [ ] **Step 4: Run the full suite**

Run: `latte clean && latte test`
Expected: PASS — 256 tests, 0 failures. No behavior change: records/`@JSONConstructor` classes have `hasReader = !read.isEmpty()` (read is always non-empty or the member is write-only) and `hasWriter = true`, so `serialize()`/`deserialize()` are unchanged; `beanConstructed` is `false`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/Component.java src/main/java/org/lattejava/json/jte/CompanionView.java \
        src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: Component write accessor + hasReader/hasWriter gating; CompanionView.beanConstructed

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Bean discovery + codegen + `finish()` branch

Make a well-formed bean round-trip. Discovery, the bean branches in `process`/`generateCompanion`, the no-arg-constructor guard, and the `finish()` template.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`, `src/main/jte/observerBody.jte`
- Create: `src/test/resources/fixtures/beans/` + `BeanCodegenTest.java`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/beans/module-info.java`:

```java
module demo.beans {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/beans/demo/Account.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public class Account {
  private String id;
  private int balance;
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public int getBalance() { return balance; }
  public void setBalance(int balance) { this.balance = balance; }
  public int getFeeBps() { return balance > 100 ? 0 : 25; }  // computed, read-only
}
```

`src/test/resources/fixtures/beans/demo/PublicFields.java` (public-field writer, no accessors):

```java
package demo;

import module org.lattejava.json;

@JSON
public class PublicFields {
  public String name;
  public boolean active;
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/BeanCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class BeanCodegenTest {
  static ProcessorHarness.Result beans;

  @BeforeClass
  public void compileOnce() throws Exception {
    beans = ProcessorHarness.compile("beans");
    assertTrue(beans.success(), beans.diagnostics().toString());
  }

  @Test
  public void beanRoundTripsViaSetters() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Account");
      Class<?> j = loader.loadClass("demo.internal.AccountJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"balance\":5,\"feeBps\":25}");
      assertEquals(t.getMethod("getId").invoke(o), "a");
      assertEquals(t.getMethod("getBalance").invoke(o), 5);
      // feeBps is read-only (computed, no setter): serialized, not written back
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"balance\":5,\"feeBps\":25}");
    }
  }

  @Test
  public void publicFieldsRoundTrip() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.PublicFields");
      Class<?> j = loader.loadClass("demo.internal.PublicFieldsJSON");
      String json = "{\"name\":\"x\",\"active\":true}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte clean && latte test --test=BeanCodegenTest`
Expected: FAIL — `@BeforeClass` compile fails: `Account` is a class with no `@JSONConstructor`, rejected ("requires a constructor annotated @JSONConstructor").

- [ ] **Step 4: Add the discovery helpers to `JSONProcessor`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, add (alphabetical among the private methods):

```java
  private boolean isBean(TypeElement type) {
    return type.getKind() == ElementKind.CLASS && jsonConstructors(type).isEmpty();
  }

  /** The class + its superclasses up to (excluding) Object, ordered base-class first. */
  private List<TypeElement> superclassChain(TypeElement type) {
    List<TypeElement> chain = new ArrayList<>();
    TypeElement t = type;
    while (t != null && !t.getQualifiedName().contentEquals("java.lang.Object")) {
      chain.add(t);
      TypeMirror sup = t.getSuperclass();
      t = sup.getKind() == TypeKind.DECLARED
          ? (TypeElement) ((javax.lang.model.type.DeclaredType) sup).asElement() : null;
    }
    java.util.Collections.reverse(chain);
    return chain;
  }

  private static String capitalize(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /** JavaBeans-style decapitalize: leaves an all-caps run (URL, ID) alone, else lowercases the first letter. */
  private static String decapitalize(String s) {
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
      return s;
    }
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  /** The property name a method exposes as a prefixed accessor, or {@code null} if it is not one. */
  private String accessorProperty(ExecutableElement m) {
    String n = m.getSimpleName().toString();
    if (m.getParameters().isEmpty() && n.length() > 3 && n.startsWith("get")
        && m.getReturnType().getKind() != TypeKind.VOID) {
      return decapitalize(n.substring(3));
    }
    if (m.getParameters().isEmpty() && n.length() > 2 && n.startsWith("is")
        && m.getReturnType().getKind() == TypeKind.BOOLEAN) {
      return decapitalize(n.substring(2));
    }
    if (m.getParameters().size() == 1 && n.length() > 3 && n.startsWith("set")) {
      return decapitalize(n.substring(3));
    }
    return null;
  }
```

- [ ] **Step 5: Add `BeanProperty` + `discoverProperties` + `beanProperty`**

Still in `JSONProcessor.java`, add (alphabetical):

```java
  /** A resolved JavaBean property: its name, type, the @JSONField/@JSONCatchAll-bearing element (or null), an element
   *  to attach errors to, and the read/write accessor facts. */
  private record BeanProperty(String name, TypeMirror type, Element config, Element at,
                              String read, String write, boolean writeSetter) {}

  /** Discovers a bean's properties (base-class first), excluding static and transient. */
  private List<BeanProperty> discoverProperties(TypeElement type) {
    List<TypeElement> chain = superclassChain(type);
    Map<String, VariableElement> fieldsByName = new LinkedHashMap<>();
    Set<String> transientNames = new HashSet<>();
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (TypeElement c : chain) {
      for (VariableElement f : javax.lang.model.util.ElementFilter.fieldsIn(c.getEnclosedElements())) {
        var mods = f.getModifiers();
        if (mods.contains(javax.lang.model.element.Modifier.STATIC)) {
          continue;
        }
        String fn = f.getSimpleName().toString();
        fieldsByName.putIfAbsent(fn, f);
        if (mods.contains(javax.lang.model.element.Modifier.TRANSIENT)) {
          transientNames.add(fn);
          continue;
        }
        if (mods.contains(javax.lang.model.element.Modifier.PUBLIC) || f.getAnnotation(JSONField.class) != null
            || f.getAnnotation(JSONCatchAll.class) != null) {
          names.add(fn);
        }
      }
      for (ExecutableElement m : javax.lang.model.util.ElementFilter.methodsIn(c.getEnclosedElements())) {
        if (!m.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
            || m.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
          continue;
        }
        String prop = accessorProperty(m);
        if (prop != null) {
          names.add(prop);
        }
      }
    }
    names.removeAll(transientNames);
    List<BeanProperty> properties = new ArrayList<>();
    for (String name : names) {
      properties.add(beanProperty(type, name, fieldsByName.get(name)));
    }
    return properties;
  }

  /** Resolves one property's accessors, type, and config element. */
  private BeanProperty beanProperty(TypeElement type, String name, VariableElement backingField) {
    String cap = capitalize(name);
    ExecutableElement getter = null;
    ExecutableElement isGetter = null;
    ExecutableElement setter = null;
    VariableElement publicField = null;
    for (Element m : processingEnv.getElementUtils().getAllMembers(type)) {
      if (!m.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
          || m.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
        continue;
      }
      if (m.getKind() == ElementKind.METHOD) {
        ExecutableElement em = (ExecutableElement) m;
        String mn = em.getSimpleName().toString();
        if (getter == null && em.getParameters().isEmpty() && mn.equals("get" + cap)
            && em.getReturnType().getKind() != TypeKind.VOID) {
          getter = em;
        } else if (isGetter == null && em.getParameters().isEmpty() && mn.equals("is" + cap)
            && em.getReturnType().getKind() == TypeKind.BOOLEAN) {
          isGetter = em;
        } else if (setter == null && em.getParameters().size() == 1 && mn.equals("set" + cap)) {
          setter = em;
        }
      } else if (publicField == null && m.getKind() == ElementKind.FIELD
          && m.getSimpleName().toString().equals(name)) {
        publicField = (VariableElement) m;
      }
    }
    String read = getter != null ? "get" + cap + "()"
        : isGetter != null ? "is" + cap + "()"
        : publicField != null ? name : "";
    String write;
    boolean writeSetter;
    if (setter != null) {
      write = "set" + cap;
      writeSetter = true;
    } else if (publicField != null) {
      write = name;
      writeSetter = false;
    } else {
      write = "";
      writeSetter = false;
    }
    TypeMirror tm = getter != null ? getter.getReturnType()
        : isGetter != null ? isGetter.getReturnType()
        : setter != null ? setter.getParameters().getFirst().asType()
        : publicField != null ? publicField.asType()
        : backingField.asType();
    Element config = configElement(backingField, getter, isGetter, setter);
    Element at = config != null ? config
        : backingField != null ? backingField
        : getter != null ? getter : setter != null ? setter : type;
    return new BeanProperty(name, tm, config, at, read, write, writeSetter);
  }

  /** The first of {@code candidates} bearing @JSONField or @JSONCatchAll, else null. */
  private Element configElement(Element... candidates) {
    for (Element e : candidates) {
      if (e != null && (e.getAnnotation(JSONField.class) != null || e.getAnnotation(JSONCatchAll.class) != null)) {
        return e;
      }
    }
    return null;
  }
```

- [ ] **Step 6: Branch `process()` and `generateCompanion` for beans**

In `process()`, the existing line that validates a class:

```java
      boolean valid = e.getKind() == ElementKind.CLASS ? validateClass(type) : validateMembers(type, type.getRecordComponents());
```

change the class branch to dispatch beans:

```java
      boolean valid = e.getKind() == ElementKind.CLASS
          ? (isBean(type) ? validateBean(type) : validateClass(type))
          : validateMembers(type, type.getRecordComponents());
```

In `generateCompanion`, the member-discovery branch (currently `if (type.getKind() == ElementKind.CLASS)`), replace it so beans build properties:

```java
    NamingStrategy naming = readNaming(type);
    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    boolean bean = isBean(type);
    if (bean) {
      for (BeanProperty p : discoverProperties(type)) {
        Component c = new Component(processingEnv, p.name(), p.type(), p.config(), naming, p.read(), p.write(), p.writeSetter());
        components.add(c);
        collectEnums(c.type(), enumImports);
      }
    } else if (type.getKind() == ElementKind.CLASS) {
      for (VariableElement p : jsonConstructors(type).getFirst().getParameters()) {
        components.add(new Component(processingEnv, p, naming, resolveRead(type, p)));
        collectEnums(new TypeView(processingEnv, p.asType()), enumImports);
      }
    } else {
      for (RecordComponentElement c : type.getRecordComponents()) {
        components.add(new Component(processingEnv, c, naming));
        collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
      }
    }
```

(`collectEnums` already accepts a `TypeView` — `c.type()` is one.)

And pass `bean` to `CompanionView` (replace the `, false);` from Task 2):

```java
        discriminatorKey, discriminatorValue, bean);
```

- [ ] **Step 7: Add a minimal `validateBean` (full validation comes in Task 4)**

Add (alphabetical, before `validateClass`):

```java
  private boolean validateBean(TypeElement type) {
    boolean hasNoArg = javax.lang.model.util.ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
        .anyMatch(c -> c.getParameters().isEmpty()
            && c.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC));
    if (!hasNoArg) {
      error(type, "@JSON class [" + type.getQualifiedName()
          + "] requires a public no-arg constructor, or a @JSONConstructor");
      return false;
    }
    List<BeanProperty> properties = discoverProperties(type);
    if (properties.isEmpty()) {
      error(type, "@JSON class [" + type.getQualifiedName() + "] has no serializable properties");
      return false;
    }
    boolean ok = true;
    for (BeanProperty p : properties) {
      if (p.read().isEmpty() && p.write().isEmpty()) {
        error(p.at(), "property [" + p.name() + "] on [" + type.getQualifiedName()
            + "] has neither a usable reader nor writer; add a getter/setter/public field");
        ok = false;
      }
    }
    return ok;
  }
```

- [ ] **Step 8: Branch `finish()` in `observerBody.jte`**

In `src/main/jte/observerBody.jte`, replace the `finish()` method (the last method) with:

```jte
  @Override public ${view.simpleName()} finish() {
@if(view.beanConstructed())
    ${view.simpleName()} value = new ${view.simpleName()}();
@for(Component c : view.components())
@if(c.deserialize())
@if(c.writeIsSetter())
    value.${c.write()}(this.${c.name()});
@else
    value.${c.write()} = this.${c.name()};
@endif
@endif
@endfor
    return value;
@else
    return new ${view.simpleName()}(@for(int i = 0; i < view.components().size(); i++)${i > 0 ? ", " : ""}this.${view.components().get(i).name()}@endfor);
@endif
  }
```

- [ ] **Step 9: Run the codegen test, then the full suite**

Run: `latte clean && latte test --test=BeanCodegenTest`
Expected: PASS — `beanRoundTripsViaSetters` (feeBps read-only) and `publicFieldsRoundTrip` green.

Run: `latte clean && latte test`
Expected: PASS — **258 tests** (256 + 2), 0 failures. Records/`@JSONConstructor`/poly unchanged (`beanConstructed` false → `finish()` keeps the constructor form).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java src/main/jte/observerBody.jte \
        src/test/resources/fixtures/beans src/test/java/org/lattejava/json/tests/processor/BeanCodegenTest.java
git commit -m "feat: @JSON JavaBean classes (property discovery, setter finish())

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Full bean validation

Factor `validateType`/`validatePolicy` out of `validateMembers` and reuse them in `validateBean`, plus bean-specific wire-key/catch-all/policy checks.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Create: reject fixtures + `BeanRejectionTest.java`

- [ ] **Step 1: Extract `validateType` and `validatePolicy` from `validateMembers`**

In `validateMembers`, replace the `@JSONField policy` block (the `JSONField policy = c.getAnnotation(JSONField.class); if (policy != null) { … }` block) with a call, and the type-support block (from `TypeView mt = new TypeView(processingEnv, c.asType());` through the final scalar `isSupportedComponentType` check) with a call:

```java
      JSONField policy = c.getAnnotation(JSONField.class);
      if (policy != null && !validatePolicy(c, c.getSimpleName(), policy, new TypeView(processingEnv, c.asType()))) {
        ok = false;
        continue;
      }
      if (!validateType(c, c.getSimpleName(), new TypeView(processingEnv, c.asType()))) {
        ok = false;
      }
```

Add the two extracted methods (alphabetical). `validatePolicy` is the conflict block, returning `false` on the first failure:

```java
  /** Validates a member's {@code @JSONField} policy (direction/format/instant conflicts) against its type. */
  private boolean validatePolicy(Element at, CharSequence name, JSONField policy, TypeView mt) {
    if (policy.readOnly() && policy.writeOnly()) {
      error(at, "@JSONField member [" + name + "] is both readOnly and writeOnly (equivalent to ignore)");
      return false;
    }
    if (policy.ignore() && (!policy.name().isEmpty() || !policy.format().isEmpty()
        || policy.readOnly() || policy.writeOnly() || policy.instant() != InstantFormat.ISO)) {
      error(at, "@JSONField member [" + name + "] combines ignore with another attribute, which has no effect");
      return false;
    }
    String typeName = mt.name();
    boolean formatType = typeName.equals("java.time.LocalDate") || typeName.equals("java.time.LocalDateTime")
        || typeName.equals("java.time.OffsetDateTime") || typeName.equals("java.time.ZonedDateTime")
        || typeName.equals("java.time.Instant");
    if (!policy.format().isEmpty()) {
      if (!formatType) {
        error(at, "@JSONField(format) on member [" + name + "] requires a LocalDate, LocalDateTime, "
            + "OffsetDateTime, ZonedDateTime, or Instant type, not [" + typeName + "]");
        return false;
      }
      if (policy.format().indexOf('"') >= 0 || policy.format().indexOf('\\') >= 0) {
        error(at, "@JSONField(format) pattern [" + policy.format() + "] on member [" + name
            + "] contains a quote or backslash");
        return false;
      }
      try {
        DateTimeFormatter.ofPattern(policy.format());
      } catch (IllegalArgumentException iae) {
        error(at, "@JSONField(format) pattern [" + policy.format() + "] on member [" + name
            + "] is not a valid DateTimeFormatter pattern: " + iae.getMessage());
        return false;
      }
    }
    if (policy.instant() != InstantFormat.ISO) {
      if (!typeName.equals("java.time.Instant")) {
        error(at, "@JSONField(instant) on member [" + name + "] requires an Instant type, not [" + typeName + "]");
        return false;
      }
      if (!policy.format().isEmpty()) {
        error(at, "@JSONField member [" + name + "] sets both instant and format (integer vs string)");
        return false;
      }
    }
    return true;
  }

  /** Validates that a member's type is serializable (collection/map/element constraints + scalar support). */
  private boolean validateType(Element at, CharSequence name, TypeView mt) {
    if (mt.isCollection()) {
      if (mt.isMap()) {
        TypeView k = mt.key();
        TypeView v = mt.value();
        if (k == null || !k.isStringForm()) {
          error(at, "@JSON member [" + name + "] has an unsupported Map key type ["
              + (k == null ? "?" : k.name()) + "] (Map key must be String, UUID, an enum, or a java.time type)");
          return false;
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
      TypeView e = mt.element();
      if (e == null || e.isCollection()) {
        error(at, "@JSON member [" + name + "] uses a nested collection ["
            + (e == null ? "?" : e.name()) + "] which is not supported in this release");
        return false;
      }
      if (!isSupportedComponentType(e)) {
        error(at, e.isRecord() && !e.isNested() ? notJSON(at, e)
            : "@JSON member [" + name + "] has an unsupported " + mt.kind() + " element type [" + e.name() + "]");
        return false;
      }
      return true;
    }
    if (!isSupportedComponentType(mt)) {
      error(at, mt.isRecord() && !mt.isNested() ? notJSON(at, mt)
          : "@JSON member [" + name + "] has unsupported type [" + mt.name() + "] (supported: primitives, "
            + "boxed primitives, String, BigInteger, BigDecimal, enums, UUID, java.time types, and @JSON records "
            + "and classes)");
      return false;
    }
    return true;
  }
```

(`notJSON` already takes `(Element, TypeView)`; its messages say "member".)

- [ ] **Step 2: Run the suite to confirm the extraction is behavior-preserving**

Run: `latte clean && latte test`
Expected: PASS — 258 tests, 0 failures. Existing rejection tests (`NestedRejectionTest`, `PolicyRejectionTest`, `CatchAllRejectionTest`, `ClassRejectionTest`) still pass — the messages now say "member" instead of "component" but those tests assert needles like `"not @JSON-annotated"`, `"readOnly and writeOnly"`, `"unsupported"`, `[bracketed]` names, not the word "component". If any asserts "component", update that single needle and note it.

- [ ] **Step 3: Commit the refactor**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: extract validateType/validatePolicy from validateMembers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Enrich `validateBean` with type/policy/wire-key/catch-all checks**

Replace the per-property loop in `validateBean` (the `for (BeanProperty p : properties)` loop) with:

```java
    boolean ok = true;
    Map<String, String> wireKeys = new HashMap<>();
    int catchAllCount = 0;
    for (BeanProperty p : properties) {
      if (p.read().isEmpty() && p.write().isEmpty()) {
        error(p.at(), "property [" + p.name() + "] on [" + type.getQualifiedName()
            + "] has neither a usable reader nor writer; add a getter/setter/public field");
        ok = false;
        continue;
      }
      TypeView mt = new TypeView(processingEnv, p.type());
      JSONField policy = p.config() == null ? null : p.config().getAnnotation(JSONField.class);
      boolean isCatchAll = p.config() != null && p.config().getAnnotation(JSONCatchAll.class) != null;
      if (isCatchAll) {
        catchAllCount++;
        if (!mt.isMap() || mt.key() == null || !mt.key().name().equals("java.lang.String")
            || mt.value() == null || !mt.value().name().equals("java.lang.Object")) {
          error(p.at(), "@JSONCatchAll property [" + p.name() + "] must be of type Map<String, Object>");
          ok = false;
        }
        if (policy != null) {
          error(p.at(), "@JSONCatchAll property [" + p.name() + "] cannot also be annotated @JSONField");
          ok = false;
        }
        continue;
      }
      String wireKey = policy != null && !policy.name().isEmpty() ? policy.name()
          : NamingStrategies.apply(readNaming(type), p.name());
      if (wireKey.chars().anyMatch(ch -> ch == '"' || ch == '\\' || ch < 0x20)) {
        error(p.at(), "JSON key [" + wireKey + "] for property [" + p.name()
            + "] contains an invalid character (quote, backslash, or control character)");
        ok = false;
        continue;
      }
      String prior = wireKeys.put(wireKey, p.name());
      if (prior != null) {
        error(p.at(), "duplicate JSON key [" + wireKey + "] on properties [" + prior + "] and [" + p.name() + "]");
        ok = false;
      }
      if (policy != null && !validatePolicy(p.at(), p.name(), policy, mt)) {
        ok = false;
        continue;
      }
      if (!validateType(p.at(), p.name(), mt)) {
        ok = false;
      }
    }
    if (catchAllCount > 1) {
      error(type, "type [" + type.getQualifiedName() + "] declares [" + catchAllCount
          + "] @JSONCatchAll properties; at most one is allowed");
      ok = false;
    }
    return ok;
```

- [ ] **Step 5: Write the rejection fixtures + test**

Each fixture's `module-info.java` is `module demo.<name> { requires static org.lattejava.json; }`.

`badbean_noctor/demo/NoCtor.java` (private no-arg → not public):

```java
package demo;

import module org.lattejava.json;

@JSON
public class NoCtor {
  private String id;
  private NoCtor() {}
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
}
```

`badbean_empty/demo/Empty.java` (no properties):

```java
package demo;

import module org.lattejava.json;

@JSON
public class Empty {
  public Empty() {}
}
```

`badbean_noaccessor/demo/NoAccessor.java` (annotated private field, no accessor):

```java
package demo;

import module org.lattejava.json;

@JSON
public class NoAccessor {
  @JSONField(name = "x") private String secret;
  public NoAccessor() {}
}
```

`badbean_unsupported/demo/Unsupported.java` (unsupported property type):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Unsupported {
  private Thread worker;
  public Unsupported() {}
  public Thread getWorker() { return worker; }
  public void setWorker(Thread worker) { this.worker = worker; }
}
```

`src/test/java/org/lattejava/json/tests/processor/BeanRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class BeanRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void noPublicNoArgCtorRejected() throws Exception {
    assertFailsWith("badbean_noctor", "requires a public no-arg constructor", "NoCtor");
  }

  @Test public void emptyBeanRejected() throws Exception {
    assertFailsWith("badbean_empty", "no serializable properties", "Empty");
  }

  @Test public void noAccessorPropertyRejected() throws Exception {
    assertFailsWith("badbean_noaccessor", "neither a usable reader nor writer", "[secret]");
  }

  @Test public void unsupportedPropertyTypeRejected() throws Exception {
    assertFailsWith("badbean_unsupported", "unsupported type", "[worker]");
  }
}
```

- [ ] **Step 6: Run the rejection test, then the full suite**

Run: `latte clean && latte test --test=BeanRejectionTest` → PASS (4 tests).

Run: `latte clean && latte test`
Expected: PASS — **262 tests** (258 + 4), 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/badbean_noctor src/test/resources/fixtures/badbean_empty \
        src/test/resources/fixtures/badbean_noaccessor src/test/resources/fixtures/badbean_unsupported \
        src/test/java/org/lattejava/json/tests/processor/BeanRejectionTest.java
git commit -m "feat: full bean validation (no-arg ctor, accessors, wire-key, catch-all, type, policy)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Composition + final verification

Prove inheritance, `transient`/`static`, field-vs-accessor config, `@JSONCatchAll`, and nesting compose.

**Files:**
- Create: fixtures under `src/test/resources/fixtures/beans/demo/`; `BeanCodegenTest.java` additions

- [ ] **Step 1: Add the composition fixtures**

`beans/demo/Employee.java` (inheritance — base `Person` with private accessor-backed fields):

```java
package demo;

import module org.lattejava.json;

class Person {
  private String name;
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
}

@JSON
public class Employee extends Person {
  private int id;
  public int getId() { return id; }
  public void setId(int id) { this.id = id; }
}
```

`beans/demo/Tagged.java` (`transient`/`static` skipped; `@JSONField` on a getter; `@JSONCatchAll` on a field):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Tagged {
  public static final String KIND = "tagged";
  private transient int cacheHits;
  private String label;
  private Map<String, Object> extras = new java.util.LinkedHashMap<>();
  public int getCacheHits() { return cacheHits; }
  public void setCacheHits(int cacheHits) { this.cacheHits = cacheHits; }
  @JSONField(name = "tag") public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  @JSONCatchAll public Map<String, Object> getExtras() { return extras; }
  public void setExtras(Map<String, Object> extras) { this.extras = extras; }
}
```

`beans/demo/Box.java` (a record nesting a bean):

```java
package demo;

import module org.lattejava.json;

@JSON
public record Box(String label, Account account) {
}
```

- [ ] **Step 2: Write the tests**

Add to `BeanCodegenTest`:

```java
  @Test
  public void inheritedPropertiesBaseFirst() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Employee");
      Class<?> j = loader.loadClass("demo.internal.EmployeeJSON");
      // base "name" first, then "id"
      String json = "{\"name\":\"a\",\"id\":7}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getName").invoke(o), "a");
      assertEquals(t.getMethod("getId").invoke(o), 7);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void transientAndStaticSkipped_configOnAccessor_catchAll() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Tagged");
      Class<?> j = loader.loadClass("demo.internal.TaggedJSON");
      // KIND (static) and cacheHits (transient) absent; label -> "tag" (getter @JSONField); x/y captured
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"tag\":\"L\",\"x\":1,\"y\":true}");
      assertEquals(t.getMethod("getLabel").invoke(o), "L");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"tag\":\"L\",\"x\":1,\"y\":true}");
    }
  }

  @Test
  public void beanNestedInRecord() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Box");
      Class<?> j = loader.loadClass("demo.internal.BoxJSON");
      String json = "{\"label\":\"b\",\"account\":{\"id\":\"a\",\"balance\":5,\"feeBps\":25}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the tests, then the full suite**

Run: `latte clean && latte test --test=BeanCodegenTest`
Expected: PASS — all five `BeanCodegenTest` tests. If `transientAndStaticSkipped...` or `beanNestedInRecord` fails, investigate the gap (e.g. `transient` detection, the `@JSONField`-on-getter config lookup, or nested-bean `isNested()` — a bean is already an `@JSON` class, so `isNested()` from the previous cycle should accept it). Report rather than altering the test.

Run: `latte clean && latte test`
Expected: PASS — **265 tests** (262 + 3), 0 failures.

- [ ] **Step 4: Spot-check generated bean companions**

Run: `find build/test/generated/beans -name 'AccountJSON.java' -o -name 'TaggedJSON.java' -o -name 'EmployeeJSON.java' | xargs -I{} sh -c 'echo "== {} =="; cat {}'`
Expected: `AccountJSON.finish()` is `Account value = new Account(); value.setId(this.id); value.setBalance(this.balance); return value;` (no `setFeeBps` — read-only), and `builder` reads `value.getFeeBps()`; `TaggedJSON` omits `cacheHits`/`KIND`, uses wire key `tag`, pre-inits the `extras` map and spreads via `value.getExtras()`; `EmployeeJSON` writes `setName` then `setId` (base-first).

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/fixtures/beans src/test/java/org/lattejava/json/tests/processor/BeanCodegenTest.java
git commit -m "test: bean inheritance, transient/static, accessor config, catch-all, nesting

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §1 recognition (no-`@JSONConstructor` class → bean; public no-arg ctor) → Task 3 (`isBean`, `process` branch), Task 4 (`validateBean` ctor check).
- §2 discovery (prefixed getters/setters/public+annotated fields; inherited base-first; `transient`/`static`/`Object` excluded) → Task 3 (`discoverProperties`, `superclassChain`, `accessorProperty`).
- §3 per-property resolution (read/write/type/config field-first; effective direction via `hasReader`/`hasWriter`) → Task 2 (`Component`), Task 3 (`beanProperty`/`configElement`).
- §4 `finish()` bean variant → Task 3 (template), Task 2 (`beanConstructed`).
- §5 validation (no-arg ctor, neither-accessor, zero properties, type, policy, wire-key, catch-all) → Task 3 (minimal) + Task 4 (full).
- §6 `METHOD` target → Task 1.
- §Testing → Task 3 (round-trip, public-field, computed read-only), Task 4 (4 rejections), Task 5 (inheritance, transient/static, accessor config, catch-all, nesting).

**Placeholder scan:** none — every step is complete code or an exact before/after.

**Type consistency:** `Component(…, String name, TypeMirror type, Element config, NamingStrategy, String read, String write, boolean writeSetter)` + `write()`/`writeIsSetter()`/`hasReader`/`hasWriter` (Task 2) used by `beanProperty`/`generateCompanion` (Task 3) and `finish()` (Task 3). `BeanProperty(name, type, config, at, read, write, writeSetter)` (Task 3) consumed by `validateBean` (Tasks 3/4) and `generateCompanion` (Task 3). `validateType`/`validatePolicy(Element at, CharSequence name, …)` (Task 4) called by `validateMembers` and `validateBean`. `CompanionView.beanConstructed()` (Task 2) used by `finish()` and set by `generateCompanion` (Task 3). `isBean`/`discoverProperties`/`superclassChain`/`accessorProperty`/`configElement` consistent across Tasks 3–4. Test counts: 254 → 256 (T1) → 256 (T2) → 258 (T3) → 262 (T4) → 265 (T5).
