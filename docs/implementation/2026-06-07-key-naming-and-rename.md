# Key Naming & Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate codegen for `@JSON(naming = …)` strategies and `@JSONField(name = …)` rename — compute a compile-time *wire key* per component (distinct from the Java name) and bake it into the generated serialize/deserialize code.

**Architecture:** A build-time `NamingStrategies` utility converts Java identifiers per strategy (acronym-aware word split → lowercase → rejoin). `Component` gains a `wireKey()` resolved from `@JSONField(name)` (verbatim) else the type's `@JSON(naming)`. The serialize/deserialize templates emit `wireKey()` as the JSON key while keeping `name()` for the Java field/accessor. Duplicate wire keys on a type are a compile-time error. `IDENTITY` (the default) reproduces today's output exactly.

**Tech Stack:** Java 25, JTE 3.2.1 templates, `javax.annotation.processing`, Latte build (`latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-07-key-naming-and-rename-design.md`

---

## Notes (read first)

- **Cycle A only.** This reads `@JSONField.name()`. The other `@JSONField` attributes (`ignore`/`required`/`readOnly`/`writeOnly`/`format`) are Cycle B and are silently inert here.
- **Wire key vs Java name.** `wireKey()` is the JSON key (case labels + builder key arguments). `name()` stays for the Java field, accessor `value.<name>()`, the `<name>ToJSON` helper, the `<Cap>MapObserver`/`<Cap>ArrayObserver` class names, and constructor args.
- **Out of scope for naming:** Map entry keys, nested-type keys (the nested type applies its own naming via its own companion), and the polymorphic discriminator key (verbatim). Those template sites keep their current values.

---

## File Structure

**Create:**
- `src/main/java/org/lattejava/json/NamingStrategies.java` — build-time converter. Lives in the **exported** `org.lattejava.json` package (not `jte`) so the test module can reach it for unit tests, mirroring the old `Template` class — `org.lattejava.json.jte` is not exported. Never a runtime helper, never in `HELPERS`, and its name matches none of `project.latte`'s copy patterns so it is not emitted into consumers.
- `src/test/java/org/lattejava/json/tests/processor/NamingStrategiesTest.java` — converter unit tests.
- `src/test/resources/fixtures/naming/` — fixture (module `demo.naming`): one record per strategy + rename + nested-composition; `module-info.java`.
- `src/test/java/org/lattejava/json/tests/processor/NamingCodegenTest.java` — round-trip tests.
- `src/test/resources/fixtures/badnaming/` — duplicate-key rejection fixture (Task 3).

**Modify:**
- `src/main/java/org/lattejava/json/jte/Component.java` — `wireKey()` + a static `wireKey(element, strategy)` resolver; constructor takes the strategy.
- `src/main/jte/companion.jte` — builder key arguments use `c.wireKey()`.
- `src/main/jte/observerBody.jte` — case labels use `c.wireKey()`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — `readNaming(record)`; pass the strategy to `Component`; duplicate-wire-key check in `validateComponents` (Task 3).

**Acceptance gate every task:** full suite green — `latte test` (currently 203).

---

## Task 1: The `NamingStrategies` converter

**Files:**
- Create: `src/main/java/org/lattejava/json/NamingStrategies.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/NamingStrategiesTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/NamingStrategiesTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class NamingStrategiesTest {
  @Test
  public void identityReturnsInputUnchanged() {
    assertEquals(NamingStrategies.apply(NamingStrategy.IDENTITY, "userName"), "userName");
    assertEquals(NamingStrategies.apply(NamingStrategy.IDENTITY, "HTTPStatus"), "HTTPStatus");
  }

  @Test
  public void snakeCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "userName"), "user_name");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "name"), "name");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "userID"), "user_id");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "parseHTTPResponse"), "parse_http_response");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "packSize2"), "pack_size2");
  }

  @Test
  public void kebabCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.KEBAB_CASE, "userName"), "user-name");
    assertEquals(NamingStrategies.apply(NamingStrategy.KEBAB_CASE, "parseHTTPResponse"), "parse-http-response");
  }

  @Test
  public void pascalCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "userName"), "UserName");
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "userID"), "UserId");
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "parseHTTPResponse"), "ParseHttpResponse");
  }

  @Test
  public void camelCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "userName"), "userName");
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "userID"), "userId");
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "HTTPStatus"), "httpStatus");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=NamingStrategiesTest`
Expected: FAIL — `NamingStrategies` does not exist (compilation failure).

- [ ] **Step 3: Implement `NamingStrategies`**

`src/main/java/org/lattejava/json/NamingStrategies.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Build-time conversion of a Java identifier to a JSON wire key per a {@link NamingStrategy}. Splits the identifier
 * into words (acronym-aware), lowercases each, and rejoins per the strategy. Build-time only — never a runtime helper,
 * never added to {@code JSONProcessor.HELPERS}. Public so the {@code org.lattejava.json.tests} module can unit-test it.
 *
 * @author Brian Pontarelli
 */
public final class NamingStrategies {
  private NamingStrategies() {
  }

  /**
   * Converts {@code javaName} to its wire key under {@code strategy}. {@code IDENTITY} returns the input unchanged.
   */
  public static String apply(NamingStrategy strategy, String javaName) {
    if (strategy == NamingStrategy.IDENTITY) {
      return javaName;
    }
    List<String> words = splitWords(javaName);
    return switch (strategy) {
      case KEBAB_CASE -> joinLower(words, "-");
      case PASCAL_CASE -> joinCapitalized(words, true);
      case CAMEL_CASE -> joinCapitalized(words, false);
      default -> joinLower(words, "_"); // SNAKE_CASE (IDENTITY handled above)
    };
  }

  /**
   * Splits a Java identifier into words. A boundary precedes an uppercase letter that follows a lowercase letter or
   * digit ({@code userName} to {@code user|Name}), and the final uppercase of an acronym run when followed by a
   * lowercase ({@code HTTPStatus} to {@code HTTP|Status}). Digits attach to the preceding word.
   */
  static List<String> splitWords(String s) {
    List<String> words = new ArrayList<>();
    int start = 0;
    for (int i = 1; i < s.length(); i++) {
      char prev = s.charAt(i - 1);
      char cur = s.charAt(i);
      boolean camelBoundary = Character.isUpperCase(cur)
          && (Character.isLowerCase(prev) || Character.isDigit(prev));
      boolean acronymBoundary = Character.isUpperCase(cur) && Character.isUpperCase(prev)
          && i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
      if (camelBoundary || acronymBoundary) {
        words.add(s.substring(start, i));
        start = i;
      }
    }
    words.add(s.substring(start));
    return words;
  }

  private static String capitalize(String word) {
    if (word.isEmpty()) {
      return word;
    }
    String lower = word.toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private static String joinCapitalized(List<String> words, boolean capitalizeFirst) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      sb.append(i == 0 && !capitalizeFirst ? words.get(i).toLowerCase(Locale.ROOT) : capitalize(words.get(i)));
    }
    return sb.toString();
  }

  private static String joinLower(List<String> words, String separator) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      if (i > 0) {
        sb.append(separator);
      }
      sb.append(words.get(i).toLowerCase(Locale.ROOT));
    }
    return sb.toString();
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=NamingStrategiesTest`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Run the full suite**

Run: `latte test`
Expected: PASS — 208 tests (203 + 5), 0 failures. (Nothing else references `NamingStrategies` yet.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/NamingStrategies.java \
        src/test/java/org/lattejava/json/tests/processor/NamingStrategiesTest.java
git commit -m "feat: add build-time NamingStrategies converter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Wire key in codegen

Resolve a `wireKey()` per component and emit it as the JSON key in serialize + deserialize.

**Files:**
- Create: `src/test/resources/fixtures/naming/module-info.java` + `demo/*.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/NamingCodegenTest.java`
- Modify: `src/main/java/org/lattejava/json/jte/Component.java`
- Modify: `src/main/jte/companion.jte`, `src/main/jte/observerBody.jte`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/naming/module-info.java`:

```java
module demo.naming {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/naming/demo/SnakeUser.java`:

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record SnakeUser(String userName, int packSize, String httpStatus) {
}
```

`src/test/resources/fixtures/naming/demo/KebabUser.java`:

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.KEBAB_CASE)
public record KebabUser(String userName) {
}
```

`src/test/resources/fixtures/naming/demo/PascalUser.java`:

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.PASCAL_CASE)
public record PascalUser(String userName) {
}
```

`src/test/resources/fixtures/naming/demo/CamelUser.java`:

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.CAMEL_CASE)
public record CamelUser(String userID) {
}
```

`src/test/resources/fixtures/naming/demo/IdentityUser.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record IdentityUser(String userName) {
}
```

`src/test/resources/fixtures/naming/demo/Renamed.java` (explicit rename, empty-name fallback):

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Renamed(String userName, @JSONField(name = "X-Request-ID") String requestId,
                      @JSONField(name = "") String fallBack) {
}
```

`src/test/resources/fixtures/naming/demo/Inner.java` + `Outer.java` (nested composition — each type uses its own strategy):

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.KEBAB_CASE)
public record Inner(String innerField) {
}
```

```java
package demo;

import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Outer(String outerName, Inner innerThing) {
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/NamingCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class NamingCodegenTest {
  static ProcessorHarness.Result naming;

  @BeforeClass
  public void compileOnce() throws Exception {
    naming = ProcessorHarness.compile("naming");
    assertTrue(naming.success(), naming.diagnostics().toString());
  }

  private String roundTrip(String type, String companion, String json) throws Exception {
    try (var loader = (URLClassLoader) naming.loader()) {
      Class<?> t = loader.loadClass(type);
      Class<?> c = loader.loadClass(companion);
      Object o = c.getMethod("fromJSON", String.class).invoke(null, json);
      return (String) c.getMethod("toJSON", t).invoke(null, o);
    }
  }

  @Test
  public void snakeCaseKeys() throws Exception {
    String json = "{\"user_name\":\"a\",\"pack_size\":3,\"http_status\":\"ok\"}";
    assertEquals(roundTrip("demo.SnakeUser", "demo.internal.SnakeUserJSON", json), json);
  }

  @Test
  public void kebabCaseKeys() throws Exception {
    String json = "{\"user-name\":\"a\"}";
    assertEquals(roundTrip("demo.KebabUser", "demo.internal.KebabUserJSON", json), json);
  }

  @Test
  public void pascalCaseKeys() throws Exception {
    String json = "{\"UserName\":\"a\"}";
    assertEquals(roundTrip("demo.PascalUser", "demo.internal.PascalUserJSON", json), json);
  }

  @Test
  public void camelCaseAcronymKey() throws Exception {
    String json = "{\"userId\":\"a\"}";
    assertEquals(roundTrip("demo.CamelUser", "demo.internal.CamelUserJSON", json), json);
  }

  @Test
  public void identityUnchanged() throws Exception {
    String json = "{\"userName\":\"a\"}";
    assertEquals(roundTrip("demo.IdentityUser", "demo.internal.IdentityUserJSON", json), json);
  }

  @Test
  public void renameOverridesStrategyAndEmptyNameFallsBack() throws Exception {
    String json = "{\"user_name\":\"a\",\"X-Request-ID\":\"b\",\"fall_back\":\"c\"}";
    assertEquals(roundTrip("demo.Renamed", "demo.internal.RenamedJSON", json), json);
  }

  @Test
  public void nestedTypeUsesItsOwnStrategy() throws Exception {
    String json = "{\"outer_name\":\"o\",\"inner_thing\":{\"inner-field\":\"i\"}}";
    assertEquals(roundTrip("demo.Outer", "demo.internal.OuterJSON", json), json);
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=NamingCodegenTest`
Expected: FAIL — keys are still the Java names (e.g. `userName`, not `user_name`), so the round-trip strings don't match (and `fromJSON` of `user_name` leaves fields unset).

- [ ] **Step 4: Add `wireKey` to `Component`**

Replace `src/main/java/org/lattejava/json/jte/Component.java` with:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

import org.lattejava.json.JSONField;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;

/**
 * Template-facing view of one {@code @JSON} record component: its Java name, its wire key (JSON key), and the
 * {@link TypeView} facts about its declared type. All serializer/observer code is assembled from these facts in the
 * JTE templates — there is no code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final String name;
  private final TypeView type;
  private final String wireKey;

  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element, NamingStrategy naming) {
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
    this.wireKey = wireKey(element, naming);
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

  public String name() {
    return name;
  }

  public TypeView type() {
    return type;
  }

  public String wireKey() {
    return wireKey;
  }
}
```

- [ ] **Step 5: Use `wireKey()` for keys in `companion.jte`**

In `src/main/jte/companion.jte`, in the `builder(...)` method, change the three component key arguments (the discriminator line is unchanged). Replace:

```jte
@if(c.type().isList() || c.type().isSet())
        .array("${c.name()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@elseif(c.type().isMap())
        .object("${c.name()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.name() + "\"", val = "value." + c.name() + "()")
@endif
```

with:

```jte
@if(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.name()}() == null ? null : ${c.name()}ToJSON(value.${c.name()}()))
@else
        .@template.memberCall(type = c.type(), key = "\"" + c.wireKey() + "\"", val = "value." + c.name() + "()")
@endif
```

- [ ] **Step 6: Use `wireKey()` for case labels in `observerBody.jte`**

Replace `src/main/jte/observerBody.jte` entirely with (every `case` label now uses `c.wireKey()`; field references, helper names, and the primitive-field diagnostic keep `c.name()`):

```jte
@import org.lattejava.json.jte.CompanionView
@import org.lattejava.json.jte.Component
@param CompanionView view
  @Override public void string(String key, String value) {
    switch (key) {
@if(!view.discriminatorKey().isEmpty())
      case "${view.discriminatorKey()}" -> { /* discriminator: ignore */ }
@endif
@for(Component c : view.components())
@if(c.type().isStringForm())
      case "${c.wireKey()}" -> this.${c.name()} = @template.fromString(type = c.type(), expr = "value");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void integer(String key, long value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "integer");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void bigInteger(String key, BigInteger value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "bigInteger");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void decimal(String key, BigDecimal value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "decimal");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void bool(String key, boolean value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isBool())
      case "${c.wireKey()}" -> this.${c.name()} = value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void nullValue(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isPrimitive())
      case "${c.wireKey()}" -> throw new JSONProcessingException("null for primitive field [${c.name()}]");
@else
      case "${c.wireKey()}" -> this.${c.name()} = null;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public JSONObjectHandler beginObject(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isMap())
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())MapObserver(); }
@elseif(c.type().hasCompanion())
      case "${c.wireKey()}" -> { return new ${c.type().nestedCompanion()}(); }
@endif
@endfor
    }
    throw new IllegalStateException("nested objects unsupported in this release");
  }
  @SuppressWarnings("unchecked")
  @Override public void object(String key, Object value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isMap() || c.type().hasCompanion())
      case "${c.wireKey()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public JSONArrayObserver<?> beginArray(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isList() || c.type().isSet())
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())ArrayObserver(); }
@endif
@endfor
    }
    throw new IllegalStateException("arrays unsupported in this release");
  }
  @SuppressWarnings("unchecked")
  @Override public void array(String key, Object value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isList() || c.type().isSet())
      case "${c.wireKey()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public ${view.simpleName()} finish() {
    return new ${view.simpleName()}(@for(int i = 0; i < view.components().size(); i++)${i > 0 ? ", " : ""}this.${view.components().get(i).name()}@endfor);
  }
```

- [ ] **Step 7: Read the naming strategy and pass it to `Component`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`:

**(a)** Add a `readNaming` helper (alphabetical among the `read*` private methods, before `readOmitNulls`):

```java
  private NamingStrategy readNaming(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann == null ? NamingStrategy.IDENTITY : ann.naming();
  }
```

**(b)** In `generateCompanion`, pass the strategy when building each `Component`. Replace the component-building loop:

```java
    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    for (RecordComponentElement c : record.getRecordComponents()) {
      components.add(new Component(processingEnv, c));
      collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
    }
```

with:

```java
    NamingStrategy naming = readNaming(record);
    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    for (RecordComponentElement c : record.getRecordComponents()) {
      components.add(new Component(processingEnv, c, naming));
      collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
    }
```

(`NamingStrategy`, `JSON` are in `org.lattejava.json`, the same package as `JSONProcessor` — no import needed. `Component` is already imported.)

- [ ] **Step 8: Run the test to verify it passes**

Run: `latte test --test=NamingCodegenTest`
Expected: PASS — all 7 tests green.

- [ ] **Step 9: Run the full suite**

Run: `latte test`
Expected: PASS — 215 tests (208 + 7), 0 failures. Every existing codegen test stays green: untouched types default to `IDENTITY`, so `wireKey()` equals `name()` and output is byte-identical (the polymorphism, nested, collection, and simple-record tests all rely on this).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/Component.java \
        src/main/jte/companion.jte src/main/jte/observerBody.jte \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/naming \
        src/test/java/org/lattejava/json/tests/processor/NamingCodegenTest.java
git commit -m "feat: wire-key codegen for @JSON naming and @JSONField rename

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Duplicate wire key validation

**Files:**
- Create: `src/test/resources/fixtures/badnaming/module-info.java` + `demo/Dup.java`
- Create test: add to `src/test/java/org/lattejava/json/tests/processor/NamingCodegenTest.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (`validateComponents`)

- [ ] **Step 1: Write the rejection fixture**

`src/test/resources/fixtures/badnaming/module-info.java`:

```java
module demo.badnaming {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/badnaming/demo/Dup.java` (two components renamed to the same key):

```java
package demo;

import module org.lattejava.json;

@JSON
public record Dup(@JSONField(name = "id") String first, @JSONField(name = "id") String second) {
}
```

- [ ] **Step 2: Write the failing test**

Add to `NamingCodegenTest`:

```java
  @Test
  public void duplicateWireKeyRejected() throws Exception {
    var r = ProcessorHarness.compile("badnaming");
    assertFalse(r.success(), "duplicate wire key must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("duplicate JSON key") && d.contains("[id]")),
        "expected a duplicate-key error for [id], got: " + r.diagnostics());
  }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=NamingCodegenTest`
Expected: FAIL — `badnaming` currently COMPILES (no duplicate-key check); `RenamedJSON`-style codegen produces a `DupJSON` with two `case "id"` labels, which is itself a javac duplicate-case error — but not the `duplicate JSON key` processor diagnostic the test asserts. (Either way `assertFalse(success)` may pass while the substring assertion fails.)

- [ ] **Step 4: Add the duplicate-wire-key check to `validateComponents`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, at the **top** of `validateComponents`, compute the naming strategy and track resolved wire keys. Change the method opening from:

```java
  private boolean validateComponents(TypeElement record) {
    boolean ok = true;
    for (RecordComponentElement c : record.getRecordComponents()) {
      TypeView type = new TypeView(processingEnv, c.asType());
```

to:

```java
  private boolean validateComponents(TypeElement record) {
    boolean ok = true;
    NamingStrategy naming = readNaming(record);
    Map<String, String> wireKeys = new HashMap<>();
    for (RecordComponentElement c : record.getRecordComponents()) {
      String wireKey = Component.wireKey(c, naming);
      String prior = wireKeys.put(wireKey, c.getSimpleName().toString());
      if (prior != null) {
        error(c, "duplicate JSON key [" + wireKey + "] on components [" + prior + "] and [" + c.getSimpleName() + "]");
        ok = false;
      }
      TypeView type = new TypeView(processingEnv, c.asType());
```

(The rest of the loop body and method are unchanged. `Map`/`HashMap` resolve via `import module java.base`; `Component` and `NamingStrategy` are already in scope.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=NamingCodegenTest`
Expected: PASS — `duplicateWireKeyRejected` green (compilation fails with the `duplicate JSON key [id]` diagnostic), and all prior `NamingCodegenTest` tests stay green.

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS — 216 tests (215 + 1), 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/resources/fixtures/badnaming \
        src/test/java/org/lattejava/json/tests/processor/NamingCodegenTest.java
git commit -m "feat: reject duplicate JSON wire keys at compile time

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full suite + spot-check**

Run: `latte test`
Expected: PASS — 216 tests, 0 failures. Confirm green: `NamingStrategiesTest`, `NamingCodegenTest`, plus the prior `SimpleRecordCodegenTest`, `NestedCodegenTest`, `MapCodegenTest`, `PolyCodegenTest`, `PolyUsageTest` (these prove `IDENTITY`-default output is unchanged).

- [ ] **Step 2: Spot-check a generated companion**

Run: `find build/test/generated/naming -name 'SnakeUserJSON.java' -exec sed -n '/private static JSONBuilder builder/,/;/p; /void string/,/^  }/p' {} \;`
Expected: the builder emits `.string("user_name", value.userName())` etc. (wire keys as literals, Java accessors), and the `string(...)` observer has `case "user_name" -> this.userName = value;`.

- [ ] **Step 3: No commit** (verification only). If any check fails, surface it to the reviewer rather than patching silently.

---

## Self-Review

**Spec coverage:**
- §1 wire key (rename verbatim, else strategy) → Task 2 (`Component.wireKey`).
- §2 flows (serialize key, deserialize case label; Java name for field/accessor/helper; non-component keys unchanged) → Task 2 (`companion.jte`, `observerBody.jte`).
- §3 word-splitting + per-strategy join → Task 1 (`NamingStrategies` + its tests, matching the worked-examples table).
- §4 duplicate wire key → compile error → Task 3.
- §5 files, §6 conventions → across tasks.
- §Testing → Task 1 (converter units), Task 2 (per-strategy round-trips, rename, fallback, nested composition), Task 3 (rejection).

**Placeholder scan:** none — every file/test/template/edit is complete code or an exact before/after.

**Type consistency:** `NamingStrategies.apply(NamingStrategy, String)` defined Task 1, used by `Component.wireKey` (Task 2) and indirectly by `validateComponents` (Task 3). `Component(processingEnv, element, naming)` + static `Component.wireKey(element, naming)` defined Task 2, the static reused in Task 3. `readNaming(TypeElement)` defined Task 2 Step 7, reused in Task 3. `wireKey()` accessor used in `companion.jte`/`observerBody.jte` (Task 2). Test counts: 203 → 208 (T1) → 215 (T2) → 216 (T3).
