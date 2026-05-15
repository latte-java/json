# Foundation: Helpers and Annotations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the runtime helper code and annotation surface for the JSON serialization library — everything the future annotation processor will copy into consumer modules. No annotation processor in this plan. All helpers are tested in this library's own TestNG suite using hand-written observers and builder calls.

**Architecture:** Six SOURCE-retention annotations (`@JSON`, `@JSONField`, `@JSONTypeInfo`, `@JSONSubtype`, `@JSONConstructor`, `@JSONCatchAll`), three observer interfaces (`JSONObserver`, `JSONArrayObserver`, `JSONPolymorphicObserver`), an observer-driven `JSONParser` with JSON-path tracking, a fluent `JSONBuilder`, four stateless helper observers (`SkipObserver`, `SkipArrayObserver`, `AnyObjectObserver`, `AnyArrayObserver`), and a `Numbers` helper for range-checked narrowing. Existing `JSONProcessor` (Map-based) and `JSONHandler` are deleted.

**Tech Stack:** Java 25, TestNG 7.10.2, Latte build (`latte build`, `latte test`, `latte test --test=<TestClass>`). Module imports (`import module java.base;`, `import module org.testng;`).

**Spec:** `docs/design/2026-05-12-serialization.md`. Defer to the spec for any decision not explicitly restated below.

---

## File Map

### Files to delete
- `src/main/java/org/lattejava/json/JSONProcessor.java` (Map-based parser; superseded by observer-driven `JSONParser`)
- `src/main/java/org/lattejava/json/JSONHandler.java` (unused interface from earlier design iteration)
- `src/test/java/org/lattejava/json/tests/JSONProcessorTest.java` (tests the old API)
- `src/test/java/org/lattejava/json/tests/model/MapperTest.java` (empty placeholder)
- `src/test/java/org/lattejava/json/tests/model/User.java` (uses `@JSON`; reintroduced in plan 2 when codegen exists)

### Files to create — main
- `src/main/java/org/lattejava/json/JSON.java` (rewrite — new attributes)
- `src/main/java/org/lattejava/json/JSONField.java`
- `src/main/java/org/lattejava/json/JSONTypeInfo.java`
- `src/main/java/org/lattejava/json/JSONSubtype.java`
- `src/main/java/org/lattejava/json/JSONConstructor.java`
- `src/main/java/org/lattejava/json/JSONCatchAll.java`
- `src/main/java/org/lattejava/json/NamingStrategy.java`
- `src/main/java/org/lattejava/json/JSONProcessingException.java`
- `src/main/java/org/lattejava/json/JSONObserver.java`
- `src/main/java/org/lattejava/json/JSONArrayObserver.java`
- `src/main/java/org/lattejava/json/JSONPolymorphicObserver.java`
- `src/main/java/org/lattejava/json/JSONParser.java`
- `src/main/java/org/lattejava/json/JSONBuilder.java`
- `src/main/java/org/lattejava/json/SkipObserver.java`
- `src/main/java/org/lattejava/json/SkipArrayObserver.java`
- `src/main/java/org/lattejava/json/AnyObjectObserver.java`
- `src/main/java/org/lattejava/json/AnyArrayObserver.java`
- `src/main/java/org/lattejava/json/Numbers.java`

### Files to modify
- `src/test/java/module-info.java` (drop obsolete `opens` for `org.lattejava.json.tests.model` and `org.lattejava.json.tests.model.internal`; keep `opens org.lattejava.json.tests`)

### Files to create — test
- `src/test/java/org/lattejava/json/tests/NumbersTest.java`
- `src/test/java/org/lattejava/json/tests/JSONBuilderTest.java`
- `src/test/java/org/lattejava/json/tests/JSONParserScalarsTest.java`
- `src/test/java/org/lattejava/json/tests/JSONParserContainersTest.java`
- `src/test/java/org/lattejava/json/tests/JSONParserErrorsTest.java`
- `src/test/java/org/lattejava/json/tests/JSONParserPolymorphismTest.java`
- `src/test/java/org/lattejava/json/tests/SkipObserverTest.java`
- `src/test/java/org/lattejava/json/tests/AnyObjectObserverTest.java`
- `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`

All Java files (main and test, including `module-info.java`) start with the project's SPDX header per `.claude/rules/copyright.md`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
```

All error messages wrap runtime values in `[brackets]` per `.claude/rules/error-messages.md`. All acronyms stay uppercase in identifiers (`JSONBuilder`, not `JsonBuilder`) per `.claude/rules/code-conventions.md`.

---

## Task 0: Delete obsolete code

**Files:**
- Delete: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Delete: `src/main/java/org/lattejava/json/JSONHandler.java`
- Delete: `src/test/java/org/lattejava/json/tests/JSONProcessorTest.java`
- Delete: `src/test/java/org/lattejava/json/tests/model/MapperTest.java`
- Delete: `src/test/java/org/lattejava/json/tests/model/User.java`
- Modify: `src/test/java/module-info.java`

- [ ] **Step 1: Delete the obsolete main and test sources**

```bash
rm src/main/java/org/lattejava/json/JSONProcessor.java
rm src/main/java/org/lattejava/json/JSONHandler.java
rm src/test/java/org/lattejava/json/tests/JSONProcessorTest.java
rm src/test/java/org/lattejava/json/tests/model/MapperTest.java
rm src/test/java/org/lattejava/json/tests/model/User.java
rmdir src/test/java/org/lattejava/json/tests/model 2>/dev/null || true
```

- [ ] **Step 2: Trim the test module-info to remove the obsolete `opens` directives**

Replace the entire contents of `src/test/java/module-info.java` with:

```java
/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json.tests {
  requires org.lattejava.json;
  requires org.testng;

  opens org.lattejava.json.tests to org.testng;
}
```

- [ ] **Step 3: Verify the project still builds cleanly with the empty source set**

Run: `latte clean && latte build`
Expected: BUILD SUCCEEDED with zero compilation errors. (Only `module-info.java` remains under main; no classes.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
chore: remove obsolete JSONProcessor and JSONHandler

Clears the slate for the observer-driven design from
docs/design/2026-05-12-serialization.md. The old Map-based parser
will be replaced by JSONParser + observer interfaces in subsequent
tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 1: `NamingStrategy` enum

**Files:**
- Create: `src/main/java/org/lattejava/json/NamingStrategy.java`

NamingStrategy is consumed by `@JSON.naming()`. It has no methods in this plan — the actual conversion logic lives in the annotation processor (plan 5). Defining it here lets the annotations compile.

- [ ] **Step 1: Create the enum**

`src/main/java/org/lattejava/json/NamingStrategy.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Strategy for converting Java field names to JSON wire-form keys. Applied by the annotation processor at
 * compile time; not consulted at runtime.
 *
 * @author The Latte Project
 */
public enum NamingStrategy {
  CAMEL_CASE,
  IDENTITY,
  KEBAB_CASE,
  PASCAL_CASE,
  SNAKE_CASE
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `latte build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/json/NamingStrategy.java
git commit -m "feat: add NamingStrategy enum for @JSON naming strategies"
```

---

## Task 2: `JSONProcessingException`

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONProcessingException.java`

- [ ] **Step 1: Create the exception class**

`src/main/java/org/lattejava/json/JSONProcessingException.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Runtime exception thrown by the JSON parser, builder, and generated companion classes on any parse or
 * serialization failure. Messages wrap runtime values in {@code [brackets]} and include the JSON-path of
 * the failure when known.
 *
 * @author The Latte Project
 */
public class JSONProcessingException extends RuntimeException {
  public JSONProcessingException(String message) {
    super(message);
  }

  public JSONProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `latte build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessingException.java
git commit -m "feat: add JSONProcessingException for parse and serialize failures"
```

---

## Task 3: All six annotations

Each annotation is small enough to share a task. Build verifies after the group; we commit once.

**Files:**
- Create: `src/main/java/org/lattejava/json/JSON.java` (overwriting the existing stub)
- Create: `src/main/java/org/lattejava/json/JSONField.java`
- Create: `src/main/java/org/lattejava/json/JSONTypeInfo.java`
- Create: `src/main/java/org/lattejava/json/JSONSubtype.java`
- Create: `src/main/java/org/lattejava/json/JSONConstructor.java`
- Create: `src/main/java/org/lattejava/json/JSONCatchAll.java`

- [ ] **Step 1: Overwrite `JSON.java`**

```java
/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record, class, or sealed interface for JSON serialization and deserialization. The annotation
 * processor generates a companion {@code *JSON} class for every type carrying this annotation.
 *
 * @author The Latte Project
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSON {
  NamingStrategy naming() default NamingStrategy.IDENTITY;

  boolean omitNulls() default true;

  boolean strict() default false;
}
```

- [ ] **Step 2: Create `JSONField.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-field configuration for a record component or class field of an {@link JSON @JSON}-annotated type.
 *
 * @author The Latte Project
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface JSONField {
  String format() default "";

  boolean ignore() default false;

  String name() default "";

  boolean readOnly() default false;

  boolean required() default false;

  boolean writeOnly() default false;
}
```

- [ ] **Step 3: Create `JSONTypeInfo.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a sealed interface or class polymorphic for JSON serialization. The discriminator property name
 * is required; OpenAPI semantics apply.
 *
 * @author The Latte Project
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONTypeInfo {
  String property();
}
```

- [ ] **Step 4: Create `JSONSubtype.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets the discriminator value for a subtype of an {@link JSONTypeInfo @JSONTypeInfo} hierarchy. Defaults
 * to the simple class name when {@link #value()} is empty.
 *
 * @author The Latte Project
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONSubtype {
  String value() default "";
}
```

- [ ] **Step 5: Create `JSONConstructor.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the constructor the annotation processor should use to deserialize a non-record class. JSON-key
 * mapping is taken from the constructor's parameter names. Not used on records; records have a canonical
 * constructor.
 *
 * @author The Latte Project
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.CONSTRUCTOR)
public @interface JSONConstructor {
}
```

- [ ] **Step 6: Create `JSONCatchAll.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Map<String, Object>} field as the catch-all bucket for unknown JSON keys. Exactly one
 * catch-all is permitted per {@link JSON @JSON} type.
 *
 * @author The Latte Project
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface JSONCatchAll {
}
```

- [ ] **Step 7: Build to verify compilation**

Run: `latte build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/lattejava/json/JSON.java \
        src/main/java/org/lattejava/json/JSONField.java \
        src/main/java/org/lattejava/json/JSONTypeInfo.java \
        src/main/java/org/lattejava/json/JSONSubtype.java \
        src/main/java/org/lattejava/json/JSONConstructor.java \
        src/main/java/org/lattejava/json/JSONCatchAll.java
git commit -m "feat: add the six @JSON-family annotations"
```

---

## Task 4: Annotation declaration tests

Reflection-based tests verifying that each annotation has the expected attributes with the expected defaults. Catches accidental signature drift.

**Files:**
- Test: `src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class AnnotationDeclarationTest {
  @Test
  public void jsonAnnotationHasExpectedAttributes() throws Exception {
    var ann = JSON.class;
    assertEquals(ann.getDeclaredMethod("naming").getDefaultValue(), NamingStrategy.IDENTITY);
    assertEquals(ann.getDeclaredMethod("omitNulls").getDefaultValue(), Boolean.TRUE);
    assertEquals(ann.getDeclaredMethod("strict").getDefaultValue(), Boolean.FALSE);
  }

  @Test
  public void jsonFieldAnnotationHasExpectedAttributes() throws Exception {
    var ann = JSONField.class;
    assertEquals(ann.getDeclaredMethod("format").getDefaultValue(), "");
    assertEquals(ann.getDeclaredMethod("ignore").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("name").getDefaultValue(), "");
    assertEquals(ann.getDeclaredMethod("readOnly").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("required").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("writeOnly").getDefaultValue(), Boolean.FALSE);
  }

  @Test
  public void jsonTypeInfoRequiresProperty() throws Exception {
    var method = JSONTypeInfo.class.getDeclaredMethod("property");
    assertNull(method.getDefaultValue(), "property() must be required (no default)");
  }

  @Test
  public void jsonSubtypeValueDefaultsToEmpty() throws Exception {
    assertEquals(JSONSubtype.class.getDeclaredMethod("value").getDefaultValue(), "");
  }

  @Test
  public void jsonConstructorHasNoAttributes() {
    assertEquals(JSONConstructor.class.getDeclaredMethods().length, 0);
  }

  @Test
  public void jsonCatchAllHasNoAttributes() {
    assertEquals(JSONCatchAll.class.getDeclaredMethods().length, 0);
  }
}
```

Note: `getDefaultValue()` is available because annotation classes are reflected even with `SOURCE` retention at *compile* time — the annotation interfaces themselves are normal Java types (compiled to `.class`), only the application of them on user code is stripped. So this test works.

- [ ] **Step 2: Run test to verify it fails (no, it should pass — but verify)**

Run: `latte test --test=AnnotationDeclarationTest`
Expected: PASS (since the annotations were created in Task 3 with these exact defaults).

If any test fails, the corresponding annotation has a different default than the design specifies — fix the annotation, not the test.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/json/tests/AnnotationDeclarationTest.java
git commit -m "test: verify annotation attribute defaults"
```

---

## Task 5: `JSONObserver` interface

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONObserver.java`

- [ ] **Step 1: Create the interface**

`src/main/java/org/lattejava/json/JSONObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Observer driven by {@link JSONParser} during deserialization of a JSON object. The annotation processor
 * generates one implementation per {@link JSON @JSON} record or class. Numeric values are delivered into
 * typed callbacks based on the parser's classification of the raw digit-run; observers should narrow
 * further only through explicit, throwing JDK calls (e.g. {@code Math.toIntExact}).
 *
 * @param <T> the constructed Java value type produced by {@link #finish()}
 * @author The Latte Project
 */
public interface JSONObserver<T> {
  JSONArrayObserver<?> beginArray(String key);

  JSONObserver<?> beginObject(String key);

  void bigInteger(String key, BigInteger value);

  void bool(String key, boolean value);

  void decimal(String key, BigDecimal value);

  T finish();

  void integer(String key, long value);

  void nullValue(String key);

  void object(String key, Object value);

  void string(String key, String value);

  void array(String key, Object value);
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `latte build`
Expected: BUILD SUCCEEDED. The interface depends on `JSONArrayObserver` which doesn't exist yet — Task 6 creates it. If you're following tasks in order, expect a compile error on `JSONArrayObserver`; resolve it by jumping to Task 6 step 1 first, then returning here.

To avoid the back-and-forth, perform Tasks 5 and 6 together: create both files before running the build.

- [ ] **Step 3: Commit (after Task 6 also has its interface in place)**

Hold the commit until Task 6 step 2.

---

## Task 6: `JSONArrayObserver` interface

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONArrayObserver.java`

- [ ] **Step 1: Create the interface**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Observer driven by {@link JSONParser} during deserialization of a JSON array. Element callbacks are
 * positional — no key parameter. Returned from a parent {@link JSONObserver#beginArray(String)} and
 * consumed in a single pass.
 *
 * @param <T> the constructed Java value type produced by {@link #finish()}
 * @author The Latte Project
 */
public interface JSONArrayObserver<T> {
  JSONArrayObserver<?> beginArray();

  JSONObserver<?> beginObject();

  void bigInteger(BigInteger value);

  void bool(boolean value);

  void decimal(BigDecimal value);

  T finish();

  void integer(long value);

  void nullValue();

  void object(Object value);

  void string(String value);

  void array(Object value);
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `latte build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit Tasks 5 and 6 together**

```bash
git add src/main/java/org/lattejava/json/JSONObserver.java \
        src/main/java/org/lattejava/json/JSONArrayObserver.java
git commit -m "feat: add JSONObserver and JSONArrayObserver interfaces"
```

---

## Task 7: `JSONPolymorphicObserver` interface

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONPolymorphicObserver.java`

- [ ] **Step 1: Create the interface**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Observer used by {@link JSONParser} to dispatch a polymorphic sealed-type hierarchy to one of its
 * permitted subtype observers. The parser scans ahead in the JSON object for the discriminator key,
 * rewinds, and parses normally into the concrete child observer returned by {@link #observerFor(String)}.
 *
 * @param <T> the sealed parent type
 * @author The Latte Project
 */
public interface JSONPolymorphicObserver<T> {
  String discriminatorKey();

  JSONObserver<? extends T> observerFor(String discriminatorValue);
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `latte build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONPolymorphicObserver.java
git commit -m "feat: add JSONPolymorphicObserver interface"
```

---

## Task 8: `Numbers` helper — `toByteExact` / `toShortExact`

`Math.toIntExact(long)` exists in the JDK; equivalents for `byte` and `short` don't. Codegen for narrowing into `byte` / `short` fields routes through these helpers. TDD: write the tests first.

**Files:**
- Create: `src/main/java/org/lattejava/json/Numbers.java`
- Test: `src/test/java/org/lattejava/json/tests/NumbersTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/NumbersTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class NumbersTest {
  @Test
  public void toByteExactAcceptsByteRangeValues() {
    assertEquals(Numbers.toByteExact(0L), (byte) 0);
    assertEquals(Numbers.toByteExact(127L), Byte.MAX_VALUE);
    assertEquals(Numbers.toByteExact(-128L), Byte.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[128\\].*\\[byte\\].*")
  public void toByteExactRejectsAboveRange() {
    Numbers.toByteExact(128L);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[-129\\].*\\[byte\\].*")
  public void toByteExactRejectsBelowRange() {
    Numbers.toByteExact(-129L);
  }

  @Test
  public void toShortExactAcceptsShortRangeValues() {
    assertEquals(Numbers.toShortExact(0L), (short) 0);
    assertEquals(Numbers.toShortExact(32767L), Short.MAX_VALUE);
    assertEquals(Numbers.toShortExact(-32768L), Short.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[32768\\].*\\[short\\].*")
  public void toShortExactRejectsAboveRange() {
    Numbers.toShortExact(32768L);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[-32769\\].*\\[short\\].*")
  public void toShortExactRejectsBelowRange() {
    Numbers.toShortExact(-32769L);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=NumbersTest`
Expected: FAIL with "cannot find symbol: Numbers".

- [ ] **Step 3: Write the implementation**

`src/main/java/org/lattejava/json/Numbers.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Range-checked narrowing helpers for primitive types not covered by the JDK's {@code Math.to*Exact}
 * methods. Each method throws {@link JSONProcessingException} when the source value is outside the target
 * type's range. Codegen calls these instead of inlining the range check at every narrowing site.
 *
 * @author The Latte Project
 */
public final class Numbers {
  private Numbers() {
  }

  public static byte toByteExact(long value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new JSONProcessingException(
          "Value [" + value + "] out of range for [byte]");
    }
    return (byte) value;
  }

  public static short toShortExact(long value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new JSONProcessingException(
          "Value [" + value + "] out of range for [short]");
    }
    return (short) value;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `latte test --test=NumbersTest`
Expected: PASS, 6 tests run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/Numbers.java \
        src/test/java/org/lattejava/json/tests/NumbersTest.java
git commit -m "feat: add Numbers.toByteExact and Numbers.toShortExact"
```

---

## Task 9: `SkipObserver` and `SkipArrayObserver`

Stateless singletons used by codegen as the `default` arm of the parent's `beginObject` and `beginArray` switches under lenient mode. Both are mutually referential (each instance returns the other's singleton from its `begin*` methods).

**Files:**
- Create: `src/main/java/org/lattejava/json/SkipObserver.java`
- Create: `src/main/java/org/lattejava/json/SkipArrayObserver.java`
- Test: `src/test/java/org/lattejava/json/tests/SkipObserverTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/SkipObserverTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class SkipObserverTest {
  @Test
  public void skipObserverIsSingleton() {
    assertSame(SkipObserver.INSTANCE, SkipObserver.INSTANCE);
  }

  @Test
  public void skipArrayObserverIsSingleton() {
    assertSame(SkipArrayObserver.INSTANCE, SkipArrayObserver.INSTANCE);
  }

  @Test
  public void skipObserverScalarCallbacksAreNoOps() {
    var s = SkipObserver.INSTANCE;
    s.string("a", "v");
    s.integer("b", 1L);
    s.bigInteger("c", BigInteger.TEN);
    s.decimal("d", BigDecimal.ONE);
    s.bool("e", true);
    s.nullValue("f");
    s.object("g", new Object());
    s.array("h", new Object());
    assertNull(s.finish(), "skip observer finish() returns null");
  }

  @Test
  public void skipObserverBeginObjectReturnsSkipObserver() {
    assertSame(SkipObserver.INSTANCE.beginObject("x"), SkipObserver.INSTANCE);
  }

  @Test
  public void skipObserverBeginArrayReturnsSkipArrayObserver() {
    assertSame(SkipObserver.INSTANCE.beginArray("x"), SkipArrayObserver.INSTANCE);
  }

  @Test
  public void skipArrayObserverScalarCallbacksAreNoOps() {
    var s = SkipArrayObserver.INSTANCE;
    s.string("v");
    s.integer(1L);
    s.bigInteger(BigInteger.TEN);
    s.decimal(BigDecimal.ONE);
    s.bool(true);
    s.nullValue();
    s.object(new Object());
    s.array(new Object());
    assertNull(s.finish(), "skip array observer finish() returns null");
  }

  @Test
  public void skipArrayObserverBeginObjectReturnsSkipObserver() {
    assertSame(SkipArrayObserver.INSTANCE.beginObject(), SkipObserver.INSTANCE);
  }

  @Test
  public void skipArrayObserverBeginArrayReturnsSkipArrayObserver() {
    assertSame(SkipArrayObserver.INSTANCE.beginArray(), SkipArrayObserver.INSTANCE);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=SkipObserverTest`
Expected: FAIL with "cannot find symbol: SkipObserver".

- [ ] **Step 3: Write `SkipArrayObserver`**

`src/main/java/org/lattejava/json/SkipArrayObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Stateless singleton {@link JSONArrayObserver} that discards every callback. Used by generated code as
 * the {@code default} target of a parent's {@code beginArray} switch under {@link JSON @JSON}'s lenient
 * default policy: unknown JSON arrays are absorbed and discarded.
 *
 * @author The Latte Project
 */
public final class SkipArrayObserver implements JSONArrayObserver<Object> {
  public static final SkipArrayObserver INSTANCE = new SkipArrayObserver();

  private SkipArrayObserver() {
  }

  @Override
  public JSONArrayObserver<?> beginArray() {
    return INSTANCE;
  }

  @Override
  public JSONObserver<?> beginObject() {
    return SkipObserver.INSTANCE;
  }

  @Override public void bigInteger(BigInteger value) {}
  @Override public void bool(boolean value) {}
  @Override public void decimal(BigDecimal value) {}

  @Override
  public Object finish() {
    return null;
  }

  @Override public void integer(long value) {}
  @Override public void nullValue() {}
  @Override public void object(Object value) {}
  @Override public void string(String value) {}
  @Override public void array(Object value) {}
}
```

- [ ] **Step 4: Write `SkipObserver`**

`src/main/java/org/lattejava/json/SkipObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Stateless singleton {@link JSONObserver} that discards every callback. Used by generated code as the
 * {@code default} target of a parent's {@code beginObject} switch under {@link JSON @JSON}'s lenient
 * default policy: unknown JSON objects are absorbed and discarded.
 *
 * @author The Latte Project
 */
public final class SkipObserver implements JSONObserver<Object> {
  public static final SkipObserver INSTANCE = new SkipObserver();

  private SkipObserver() {
  }

  @Override
  public JSONArrayObserver<?> beginArray(String key) {
    return SkipArrayObserver.INSTANCE;
  }

  @Override
  public JSONObserver<?> beginObject(String key) {
    return INSTANCE;
  }

  @Override public void bigInteger(String key, BigInteger value) {}
  @Override public void bool(String key, boolean value) {}
  @Override public void decimal(String key, BigDecimal value) {}

  @Override
  public Object finish() {
    return null;
  }

  @Override public void integer(String key, long value) {}
  @Override public void nullValue(String key) {}
  @Override public void object(String key, Object value) {}
  @Override public void string(String key, String value) {}
  @Override public void array(String key, Object value) {}
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `latte test --test=SkipObserverTest`
Expected: PASS, 8 tests run.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/SkipObserver.java \
        src/main/java/org/lattejava/json/SkipArrayObserver.java \
        src/test/java/org/lattejava/json/tests/SkipObserverTest.java
git commit -m "feat: add SkipObserver and SkipArrayObserver singletons"
```

---

## Task 10: `AnyObjectObserver` and `AnyArrayObserver`

Per-instance accumulators for the catch-all path. Each instance materializes its own `LinkedHashMap<String, Object>` (object) or `ArrayList<Object>` (array). Nested objects/arrays are absorbed via fresh instances.

**Files:**
- Create: `src/main/java/org/lattejava/json/AnyObjectObserver.java`
- Create: `src/main/java/org/lattejava/json/AnyArrayObserver.java`
- Test: `src/test/java/org/lattejava/json/tests/AnyObjectObserverTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/AnyObjectObserverTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class AnyObjectObserverTest {
  @Test
  public void capturesScalarsByKey() {
    var obs = new AnyObjectObserver();
    obs.string("name", "Alice");
    obs.integer("age", 30L);
    obs.bigInteger("big", new BigInteger("99999999999999999999"));
    obs.decimal("price", new BigDecimal("12.5"));
    obs.bool("active", true);
    obs.nullValue("opt");

    Map<String, Object> result = obs.finish();
    assertEquals(result.get("name"), "Alice");
    assertEquals(result.get("age"), 30L);
    assertEquals(result.get("big"), new BigInteger("99999999999999999999"));
    assertEquals(result.get("price"), new BigDecimal("12.5"));
    assertEquals(result.get("active"), Boolean.TRUE);
    assertTrue(result.containsKey("opt"));
    assertNull(result.get("opt"));
  }

  @Test
  public void preservesInsertionOrder() {
    var obs = new AnyObjectObserver();
    obs.string("c", "3");
    obs.string("a", "1");
    obs.string("b", "2");
    var keys = new ArrayList<>(obs.finish().keySet());
    assertEquals(keys, List.of("c", "a", "b"));
  }

  @Test
  public void beginObjectReturnsFreshAnyObjectObserver() {
    var parent = new AnyObjectObserver();
    var child = parent.beginObject("nested");
    assertTrue(child instanceof AnyObjectObserver);
    assertNotSame(child, parent);
  }

  @Test
  public void objectStoresChildResultUnderKey() {
    var parent = new AnyObjectObserver();
    var child = (AnyObjectObserver) parent.beginObject("nested");
    child.string("inner", "v");
    parent.object("nested", child.finish());
    Map<String, Object> result = parent.finish();
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) result.get("nested");
    assertEquals(nested.get("inner"), "v");
  }

  @Test
  public void beginArrayReturnsFreshAnyArrayObserver() {
    var parent = new AnyObjectObserver();
    var arr = parent.beginArray("items");
    assertTrue(arr instanceof AnyArrayObserver);
  }

  @Test
  public void arrayStoresListUnderKey() {
    var parent = new AnyObjectObserver();
    var arr = (AnyArrayObserver) parent.beginArray("items");
    arr.string("a");
    arr.string("b");
    parent.array("items", arr.finish());
    Map<String, Object> result = parent.finish();
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) result.get("items");
    assertEquals(items, List.of("a", "b"));
  }

  @Test
  public void anyArrayObserverAccumulatesElements() {
    var obs = new AnyArrayObserver();
    obs.string("x");
    obs.integer(1L);
    obs.bool(false);
    obs.nullValue();
    List<Object> result = obs.finish();
    assertEquals(result.size(), 4);
    assertEquals(result.get(0), "x");
    assertEquals(result.get(1), 1L);
    assertEquals(result.get(2), Boolean.FALSE);
    assertNull(result.get(3));
  }

  @Test
  public void anyArrayObserverNestedObjectsAndArrays() {
    var arr = new AnyArrayObserver();
    var innerObj = (AnyObjectObserver) arr.beginObject();
    innerObj.string("k", "v");
    arr.object(innerObj.finish());

    var innerArr = (AnyArrayObserver) arr.beginArray();
    innerArr.integer(7L);
    arr.array(innerArr.finish());

    List<Object> result = arr.finish();
    assertEquals(result.size(), 2);
    @SuppressWarnings("unchecked")
    Map<String, Object> obj = (Map<String, Object>) result.get(0);
    assertEquals(obj.get("k"), "v");
    @SuppressWarnings("unchecked")
    List<Object> nestedArr = (List<Object>) result.get(1);
    assertEquals(nestedArr, List.of(7L));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=AnyObjectObserverTest`
Expected: FAIL with "cannot find symbol: AnyObjectObserver".

- [ ] **Step 3: Write `AnyArrayObserver`**

`src/main/java/org/lattejava/json/AnyArrayObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JSONArrayObserver} that accumulates every element into an {@link ArrayList} of the element's
 * natural Java shape ({@code String}, {@code Long}, {@code BigInteger}, {@code BigDecimal},
 * {@code Boolean}, {@code null}, {@code LinkedHashMap<String, Object>} for nested objects, nested
 * {@code ArrayList<Object>} for nested arrays). One instance per array; not thread-safe.
 *
 * @author The Latte Project
 */
public final class AnyArrayObserver implements JSONArrayObserver<List<Object>> {
  private final List<Object> list = new ArrayList<>();

  @Override
  public JSONArrayObserver<?> beginArray() {
    return new AnyArrayObserver();
  }

  @Override
  public JSONObserver<?> beginObject() {
    return new AnyObjectObserver();
  }

  @Override public void bigInteger(BigInteger value) { list.add(value); }
  @Override public void bool(boolean value)          { list.add(value); }
  @Override public void decimal(BigDecimal value)    { list.add(value); }

  @Override
  public List<Object> finish() {
    return list;
  }

  @Override public void integer(long value)          { list.add(value); }
  @Override public void nullValue()                  { list.add(null); }
  @Override public void object(Object value)         { list.add(value); }
  @Override public void string(String value)         { list.add(value); }
  @Override public void array(Object value)          { list.add(value); }
}
```

- [ ] **Step 4: Write `AnyObjectObserver`**

`src/main/java/org/lattejava/json/AnyObjectObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link JSONObserver} that accumulates every key/value pair into a {@link LinkedHashMap} of the value's
 * natural Java shape (same mapping as {@link AnyArrayObserver}). Preserves JSON-object insertion order.
 * One instance per JSON object; not thread-safe.
 *
 * @author The Latte Project
 */
public final class AnyObjectObserver implements JSONObserver<Map<String, Object>> {
  private final Map<String, Object> map = new LinkedHashMap<>();

  @Override
  public JSONArrayObserver<?> beginArray(String key) {
    return new AnyArrayObserver();
  }

  @Override
  public JSONObserver<?> beginObject(String key) {
    return new AnyObjectObserver();
  }

  @Override public void bigInteger(String key, BigInteger value) { map.put(key, value); }
  @Override public void bool(String key, boolean value)          { map.put(key, value); }
  @Override public void decimal(String key, BigDecimal value)    { map.put(key, value); }

  @Override
  public Map<String, Object> finish() {
    return map;
  }

  @Override public void integer(String key, long value)          { map.put(key, value); }
  @Override public void nullValue(String key)                    { map.put(key, null); }
  @Override public void object(String key, Object value)         { map.put(key, value); }
  @Override public void string(String key, String value)         { map.put(key, value); }
  @Override public void array(String key, Object value)          { map.put(key, value); }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `latte test --test=AnyObjectObserverTest`
Expected: PASS, 8 tests run.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/AnyObjectObserver.java \
        src/main/java/org/lattejava/json/AnyArrayObserver.java \
        src/test/java/org/lattejava/json/tests/AnyObjectObserverTest.java
git commit -m "feat: add AnyObjectObserver and AnyArrayObserver for catch-all use"
```

---

## Task 11: `JSONBuilder` — scaffold + scalar writers + omit-nulls

The builder is fluent. It writes directly to a `ByteArrayOutputStream`. Each `*(String key, ...)` method (a) emits a leading comma if not the first member, (b) emits the quoted key, (c) emits `:`, (d) emits the value. `build()` finalizes with `}` and decodes to a `String`. `buildBytes()` returns the raw `byte[]`. Empty/null values are omitted by default (matches `@JSON.omitNulls = true`); `JSONBuilder` exposes a constructor flag to disable omission for tests and for the codegen of types declaring `@JSON(omitNulls = false)`.

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONBuilder.java`
- Test: `src/test/java/org/lattejava/json/tests/JSONBuilderTest.java`

- [ ] **Step 1: Write the failing test (start small — single string field)**

`src/test/java/org/lattejava/json/tests/JSONBuilderTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONBuilderTest {
  @Test
  public void emptyObjectBuilds() {
    assertEquals(new JSONBuilder().build(), "{}");
  }

  @Test
  public void singleStringMember() {
    assertEquals(new JSONBuilder().string("name", "Alice").build(),
                 "{\"name\":\"Alice\"}");
  }

  @Test
  public void twoStringMembers() {
    assertEquals(
        new JSONBuilder().string("a", "1").string("b", "2").build(),
        "{\"a\":\"1\",\"b\":\"2\"}");
  }

  @Test
  public void integerLongShortByte() {
    String json = new JSONBuilder()
        .integer("i", 42L)
        .integer("zero", 0L)
        .integer("neg", -100L)
        .build();
    assertEquals(json, "{\"i\":42,\"zero\":0,\"neg\":-100}");
  }

  @Test
  public void bigIntegerAndDecimal() {
    String json = new JSONBuilder()
        .bigInteger("b", new BigInteger("99999999999999999999"))
        .decimal("d", new BigDecimal("12.5"))
        .build();
    assertEquals(json, "{\"b\":99999999999999999999,\"d\":12.5}");
  }

  @Test
  public void booleanAndNull() {
    String json = new JSONBuilder(false /* emit nulls */)
        .bool("active", true)
        .bool("inactive", false)
        .nullValue("none")
        .build();
    assertEquals(json, "{\"active\":true,\"inactive\":false,\"none\":null}");
  }

  @Test
  public void omitNullsByDefault() {
    String json = new JSONBuilder()
        .string("present", "x")
        .nullValue("absent")
        .build();
    assertEquals(json, "{\"present\":\"x\"}");
  }

  @Test
  public void stringEscapes() {
    String json = new JSONBuilder()
        .string("quote", "\"")
        .string("backslash", "\\")
        .string("newline", "\n")
        .string("tab", "\t")
        .string("control", "")
        .build();
    assertEquals(json,
        "{\"quote\":\"\\\"\",\"backslash\":\"\\\\\",\"newline\":\"\\n\",\"tab\":\"\\t\",\"control\":\"\\u0001\"}");
  }

  @Test
  public void unicodeAboveBmpEmittedAsUtf8Bytes() {
    String json = new JSONBuilder().string("emoji", "😀").build();
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    // U+1F600 GRINNING FACE — UTF-8: F0 9F 98 80
    int idx = json.indexOf("emoji") + "emoji\":\"".length();
    String emojiPart = json.substring(idx, idx + 2);
    assertEquals(emojiPart, "😀");
  }

  @Test
  public void rawObjectMemberEmbedsJsonString() {
    String addressJson = "{\"city\":\"Boulder\"}";
    String json = new JSONBuilder()
        .string("name", "Alice")
        .object("address", addressJson)
        .build();
    assertEquals(json, "{\"name\":\"Alice\",\"address\":{\"city\":\"Boulder\"}}");
  }

  @Test
  public void rawArrayMemberEmbedsJsonString() {
    String arrJson = "[1,2,3]";
    String json = new JSONBuilder().array("tags", arrJson).build();
    assertEquals(json, "{\"tags\":[1,2,3]}");
  }

  @Test
  public void omitEmptyCollectionsRepresentedAsNullRawJSON() {
    // The codegen passes null for null-or-empty collections under omitNulls=true.
    String json = new JSONBuilder()
        .string("a", "x")
        .array("empty", null)
        .object("missing", null)
        .build();
    assertEquals(json, "{\"a\":\"x\"}");
  }

  @Test
  public void buildBytesProducesUtf8() {
    byte[] bytes = new JSONBuilder().string("a", "x").buildBytes();
    assertEquals(new String(bytes, StandardCharsets.UTF_8), "{\"a\":\"x\"}");
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*[Nn]on-finite.*")
  public void rejectsNaNDoubleViaDecimal() {
    // Builder receives a BigDecimal, but for completeness the test verifies the analogous behavior
    // by routing a Double through a hypothetical convenience overload added by the codegen path.
    // This test guards against any future `decimal(Double)` accepting NaN.
    new JSONBuilder().decimal("d", BigDecimal.valueOf(Double.NaN));
  }
}
```

Note: the last test verifies an edge case — `BigDecimal.valueOf(Double.NaN)` throws `NumberFormatException` before reaching the builder. The test is included as a guard; if `BigDecimal.valueOf` were to return something for NaN in a future JDK version, the builder would need to reject it. Mark it `@Test(enabled = false)` if it fails for the wrong reason — coverage of NaN serialization is handled by the codegen layer in plan 5 anyway.

Actually, on reflection: `BigDecimal.valueOf(Double.NaN)` throws `NumberFormatException`, not `JSONProcessingException`. The test as written would fail. Remove it from this task; NaN handling belongs to the codegen-side double conversion, not the builder.

Updated: delete the `rejectsNaNDoubleViaDecimal` test from the test file before running.

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=JSONBuilderTest`
Expected: FAIL with "cannot find symbol: JSONBuilder".

- [ ] **Step 3: Write the implementation**

`src/main/java/org/lattejava/json/JSONBuilder.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Fluent builder for JSON objects. Writes UTF-8 bytes directly to a {@link ByteArrayOutputStream};
 * {@link #build()} decodes to a {@link String}, {@link #buildBytes()} returns the raw bytes. Generated
 * companion code calls these methods in source order; field order on the wire matches Java declaration
 * order.
 *
 * <p>By default null values and {@code null}-passed raw JSON members are omitted, matching
 * {@link JSON @JSON}'s {@code omitNulls = true} default. Pass {@code false} to the constructor to emit
 * them faithfully.
 *
 * @author The Latte Project
 */
public final class JSONBuilder {
  private final boolean omitNulls;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
  private boolean first = true;

  public JSONBuilder() {
    this(true);
  }

  public JSONBuilder(boolean omitNulls) {
    this.omitNulls = omitNulls;
    out.write('{');
  }

  public JSONBuilder array(String key, String rawJson) {
    if (rawJson == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(rawJson);
    return this;
  }

  public JSONBuilder bigInteger(String key, BigInteger value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value.toString());
    return this;
  }

  public JSONBuilder bool(String key, boolean value) {
    writeKey(key);
    writeRaw(value ? "true" : "false");
    return this;
  }

  public byte[] buildBytes() {
    out.write('}');
    return out.toByteArray();
  }

  public String build() {
    return new String(buildBytes(), StandardCharsets.UTF_8);
  }

  public JSONBuilder decimal(String key, BigDecimal value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value.toPlainString());
    return this;
  }

  public JSONBuilder integer(String key, long value) {
    writeKey(key);
    writeRaw(Long.toString(value));
    return this;
  }

  public JSONBuilder nullValue(String key) {
    if (omitNulls) {
      return this;
    }
    writeKey(key);
    writeRaw("null");
    return this;
  }

  public JSONBuilder object(String key, String rawJson) {
    if (rawJson == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(rawJson);
    return this;
  }

  public JSONBuilder string(String key, String value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeString(value);
    return this;
  }

  private JSONBuilder omittedNull(String key) {
    if (omitNulls) {
      return this;
    }
    writeKey(key);
    writeRaw("null");
    return this;
  }

  private void writeKey(String key) {
    if (first) {
      first = false;
    } else {
      out.write(',');
    }
    writeString(key);
    out.write(':');
  }

  private void writeRaw(String literal) {
    try {
      out.write(literal.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new JSONProcessingException("Serialization I/O failure", e);
    }
  }

  private void writeString(String s) {
    out.write('"');
    int len = s.length();
    int i = 0;
    while (i < len) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\' || c < 0x20) {
        switch (c) {
          case '"'  -> { out.write('\\'); out.write('"'); }
          case '\\' -> { out.write('\\'); out.write('\\'); }
          case '\b' -> { out.write('\\'); out.write('b'); }
          case '\f' -> { out.write('\\'); out.write('f'); }
          case '\n' -> { out.write('\\'); out.write('n'); }
          case '\r' -> { out.write('\\'); out.write('r'); }
          case '\t' -> { out.write('\\'); out.write('t'); }
          default -> writeRaw(String.format("\\u%04x", (int) c));
        }
        i++;
      } else {
        int runStart = i;
        while (i < len) {
          char d = s.charAt(i);
          if (d == '"' || d == '\\' || d < 0x20) break;
          i++;
        }
        writeRaw(s.substring(runStart, i));
      }
    }
    out.write('"');
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `latte test --test=JSONBuilderTest`
Expected: PASS, 12 tests run (after removing the NaN test as noted in Step 1).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONBuilder.java \
        src/test/java/org/lattejava/json/tests/JSONBuilderTest.java
git commit -m "feat: add fluent JSONBuilder with omit-nulls default"
```

---

## Task 12: `JSONParser` — skeleton, whitespace, top-level object, scalars

The parser is observer-driven. Input is a `String` (decoded from `byte[]` for the convenience overload). Position is an `int` cursor. JSON-path tracking is added as a per-parse `ArrayDeque<String>`. This task implements: the entry points (`parse(String, JSONObserver)`, `parse(byte[], JSONObserver)`), whitespace handling, top-level object enforcement, scalar callbacks (`string`, `integer`, `bigInteger`, `decimal`, `bool`, `nullValue`), and error messages with byte offset and path.

This is the largest single task in the plan. We test incrementally as we add features.

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONParser.java`
- Test: `src/test/java/org/lattejava/json/tests/JSONParserScalarsTest.java`

- [ ] **Step 1: Write the failing test for scalars + top-level object**

`src/test/java/org/lattejava/json/tests/JSONParserScalarsTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserScalarsTest {

  /** Hand-written observer that records every callback in order for inspection. */
  static final class RecorderObserver implements JSONObserver<Map<String, Object>> {
    final Map<String, Object> map = new LinkedHashMap<>();
    final List<String> callLog = new ArrayList<>();

    @Override public JSONArrayObserver<?> beginArray(String key) { throw new AssertionError("no array expected"); }
    @Override public JSONObserver<?> beginObject(String key)     { throw new AssertionError("no nested object expected"); }
    @Override public void bigInteger(String key, BigInteger value) { callLog.add("bigInteger:" + key); map.put(key, value); }
    @Override public void bool(String key, boolean value)          { callLog.add("bool:" + key); map.put(key, value); }
    @Override public void decimal(String key, BigDecimal value)    { callLog.add("decimal:" + key); map.put(key, value); }
    @Override public Map<String, Object> finish()                  { callLog.add("finish"); return map; }
    @Override public void integer(String key, long value)          { callLog.add("integer:" + key); map.put(key, value); }
    @Override public void nullValue(String key)                    { callLog.add("nullValue:" + key); map.put(key, null); }
    @Override public void object(String key, Object value)         { callLog.add("object:" + key); map.put(key, value); }
    @Override public void string(String key, String value)         { callLog.add("string:" + key); map.put(key, value); }
    @Override public void array(String key, Object value)          { callLog.add("array:" + key); map.put(key, value); }
  }

  static <T> T parse(String json, JSONObserver<T> obs) {
    return new JSONParser().parse(json, obs);
  }

  @Test
  public void emptyObject() {
    var r = new RecorderObserver();
    parse("{}", r);
    assertTrue(r.map.isEmpty());
    assertEquals(r.callLog, List.of("finish"));
  }

  @Test
  public void singleStringMember() {
    var r = new RecorderObserver();
    parse("{\"name\":\"Alice\"}", r);
    assertEquals(r.map.get("name"), "Alice");
  }

  @Test
  public void twoMembersDifferentTypes() {
    var r = new RecorderObserver();
    parse("{\"name\":\"Alice\",\"age\":30}", r);
    assertEquals(r.map.get("name"), "Alice");
    assertEquals(r.map.get("age"), 30L);
  }

  @Test
  public void integerLongFastPath() {
    var r = new RecorderObserver();
    parse("{\"a\":0,\"b\":-1,\"c\":9223372036854775807}", r);
    assertEquals(r.map.get("a"), 0L);
    assertEquals(r.map.get("b"), -1L);
    assertEquals(r.map.get("c"), 9223372036854775807L);
  }

  @Test
  public void integerOverNineteenDigitsBecomesBigInteger() {
    var r = new RecorderObserver();
    parse("{\"big\":99999999999999999999}", r);
    assertEquals(r.map.get("big"), new BigInteger("99999999999999999999"));
  }

  @Test
  public void numberWithDecimalBecomesBigDecimal() {
    var r = new RecorderObserver();
    parse("{\"d\":12.5}", r);
    assertEquals(r.map.get("d"), new BigDecimal("12.5"));
  }

  @Test
  public void numberWithExponentBecomesBigDecimal() {
    var r = new RecorderObserver();
    parse("{\"d\":1e3}", r);
    assertEquals(r.map.get("d"), new BigDecimal("1e3"));
  }

  @Test
  public void booleansAndNull() {
    var r = new RecorderObserver();
    parse("{\"t\":true,\"f\":false,\"n\":null}", r);
    assertEquals(r.map.get("t"), Boolean.TRUE);
    assertEquals(r.map.get("f"), Boolean.FALSE);
    assertTrue(r.map.containsKey("n"));
    assertNull(r.map.get("n"));
  }

  @Test
  public void stringEscapesParsed() {
    var r = new RecorderObserver();
    parse("{\"s\":\"a\\\"b\\\\c\\nd\\t\"}", r);
    assertEquals(r.map.get("s"), "a\"b\\c\nd\t");
  }

  @Test
  public void unicodeEscape() {
    var r = new RecorderObserver();
    parse("{\"s\":\"\\u0041\"}", r);
    assertEquals(r.map.get("s"), "A");
  }

  @Test
  public void surrogatePair() {
    var r = new RecorderObserver();
    parse("{\"s\":\"\\uD83D\\uDE00\"}", r);
    assertEquals(r.map.get("s"), "😀");
  }

  @Test
  public void whitespaceTolerated() {
    var r = new RecorderObserver();
    parse("  {  \"a\"  :  1  ,  \"b\"  :  2  }  ", r);
    assertEquals(r.map.get("a"), 1L);
    assertEquals(r.map.get("b"), 2L);
  }

  @Test
  public void parseFromBytesUtf8() {
    var r = new RecorderObserver();
    byte[] bytes = "{\"s\":\"héllo\"}".getBytes(StandardCharsets.UTF_8);
    new JSONParser().parse(bytes, r);
    assertEquals(r.map.get("s"), "héllo");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=JSONParserScalarsTest`
Expected: FAIL with "cannot find symbol: JSONParser".

- [ ] **Step 3: Write `JSONParser` — scaffold + scalar parsing + top-level enforcement**

`src/main/java/org/lattejava/json/JSONParser.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Observer-driven JSON parser. Walks a {@link String} cursor and emits typed callbacks on a target
 * {@link JSONObserver}. Maintains a JSON-path stack for diagnostic context on thrown
 * {@link JSONProcessingException}s. Top-level JSON value must be an object; arrays, strings, numbers,
 * booleans, and {@code null} at the top level are rejected (the library targets OpenAPI DTOs and JWT
 * payloads, both of which guarantee object envelopes).
 *
 * @author The Latte Project
 */
public final class JSONParser {
  private final int maxNestingDepth;

  private final ArrayDeque<String> path = new ArrayDeque<>();
  private int len;
  private int pos;
  private String src;

  public JSONParser() {
    this(64);
  }

  public JSONParser(int maxNestingDepth) {
    if (maxNestingDepth <= 0) {
      throw new IllegalArgumentException(
          "maxNestingDepth must be > 0 but found [" + maxNestingDepth + "]");
    }
    this.maxNestingDepth = maxNestingDepth;
  }

  public <T> T parse(byte[] bytes, JSONObserver<T> target) {
    if (bytes == null) {
      throw new JSONProcessingException("Input bytes are null");
    }
    return parse(new String(bytes, StandardCharsets.UTF_8), target);
  }

  public <T> T parse(String json, JSONObserver<T> target) {
    if (json == null) {
      throw new JSONProcessingException("Input string is null");
    }
    if (target == null) {
      throw new JSONProcessingException("Observer is null");
    }
    this.src = json;
    this.len = json.length();
    this.pos = 0;
    this.path.clear();

    skipWhitespace();
    if (pos >= len) {
      throw error("Empty input");
    }
    if (peek() != '{') {
      throw error("Expected top-level JSON object but found [" + peek() + "]");
    }
    parseObjectInto(target, 0);
    skipWhitespace();
    if (pos != len) {
      throw error("Trailing content after JSON value");
    }
    return target.finish();
  }

  private JSONProcessingException error(String message) {
    String p = path.isEmpty() ? "$" : pathString();
    return new JSONProcessingException(
        message + " at path [" + p + "] position [" + pos + "]");
  }

  private void expect(char c) {
    if (pos >= len) {
      throw error("Expected [" + c + "] but reached end of input");
    }
    if (src.charAt(pos) != c) {
      throw error("Expected [" + c + "] but found [" + src.charAt(pos) + "]");
    }
    pos++;
  }

  private int parseHex4() {
    if (pos + 4 > len) {
      throw error("Truncated \\u escape");
    }
    int code = 0;
    for (int i = 0; i < 4; i++) {
      char c = src.charAt(pos++);
      int d;
      if (c >= '0' && c <= '9')      d = c - '0';
      else if (c >= 'a' && c <= 'f') d = 10 + (c - 'a');
      else if (c >= 'A' && c <= 'F') d = 10 + (c - 'A');
      else throw error("Invalid hex digit [" + c + "] in \\u escape");
      code = (code << 4) | d;
    }
    return code;
  }

  private void parseLiteral(String literal) {
    if (pos + literal.length() > len
        || !src.regionMatches(pos, literal, 0, literal.length())) {
      throw error("Invalid literal at position [" + pos + "]");
    }
    pos += literal.length();
  }

  private Number parseNumber() {
    int start = pos;
    int digitCount = 0;
    boolean hasDecimal = false;
    boolean hasExponent = false;

    if (src.charAt(pos) == '-') {
      pos++;
      if (pos >= len) throw error("Number ends after [-]");
    }
    char c = src.charAt(pos);
    if (c == '0') { pos++; digitCount++; }
    else if (c >= '1' && c <= '9') {
      while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        pos++; digitCount++;
      }
    } else {
      throw error("Invalid number");
    }
    if (pos < len && src.charAt(pos) == '.') {
      hasDecimal = true;
      pos++;
      int fracStart = pos;
      while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        pos++; digitCount++;
      }
      if (pos == fracStart) throw error("Number has [.] with no fractional digits");
    }
    if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
      hasExponent = true;
      pos++;
      if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
      int expStart = pos;
      while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        pos++; digitCount++;
      }
      if (pos == expStart) throw error("Number has exponent marker with no exponent digits");
    }

    try {
      if (hasDecimal || hasExponent) {
        return new BigDecimal(src.substring(start, pos));
      }
      if (digitCount <= 18) {
        return Long.parseLong(src, start, pos, 10);
      }
      return new BigInteger(src.substring(start, pos));
    } catch (NumberFormatException e) {
      throw new JSONProcessingException(
          "Invalid number [" + src.substring(start, pos) + "] at path ["
              + (path.isEmpty() ? "$" : pathString()) + "]", e);
    }
  }

  private <T> void parseObjectInto(JSONObserver<T> target, int depth) {
    if (depth > maxNestingDepth) {
      throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
    }
    expect('{');
    skipWhitespace();
    if (pos < len && src.charAt(pos) == '}') {
      pos++;
      return;
    }
    while (true) {
      skipWhitespace();
      if (pos >= len || src.charAt(pos) != '"') {
        throw error("Expected string key");
      }
      String key = parseString();
      skipWhitespace();
      expect(':');
      parseValue(target, key, depth);
      skipWhitespace();
      if (pos >= len) throw error("Unterminated object");
      char nc = src.charAt(pos);
      if (nc == ',') { pos++; continue; }
      if (nc == '}') { pos++; return; }
      throw error("Expected [,] or [}] but found [" + nc + "]");
    }
  }

  private String parseString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (pos < len) {
      char c = src.charAt(pos++);
      if (c == '"') return sb.toString();
      if (c == '\\') {
        if (pos >= len) throw error("Unterminated escape sequence");
        char esc = src.charAt(pos++);
        switch (esc) {
          case '"'  -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/'  -> sb.append('/');
          case 'b'  -> sb.append('\b');
          case 'f'  -> sb.append('\f');
          case 'n'  -> sb.append('\n');
          case 'r'  -> sb.append('\r');
          case 't'  -> sb.append('\t');
          case 'u'  -> {
            int code = parseHex4();
            if (Character.isHighSurrogate((char) code)) {
              if (pos + 1 >= len || src.charAt(pos) != '\\' || src.charAt(pos + 1) != 'u') {
                throw error("Lone high surrogate [\\u" + Integer.toHexString(code) + "]");
              }
              pos += 2;
              int low = parseHex4();
              if (!Character.isLowSurrogate((char) low)) {
                throw error("High surrogate not followed by low surrogate");
              }
              sb.append((char) code).append((char) low);
            } else if (Character.isLowSurrogate((char) code)) {
              throw error("Lone low surrogate [\\u" + Integer.toHexString(code) + "]");
            } else {
              sb.append((char) code);
            }
          }
          default -> throw error("Invalid escape [\\" + esc + "]");
        }
      } else if (c < 0x20) {
        throw error("Unescaped control character [U+" + String.format("%04X", (int) c) + "] in string");
      } else {
        sb.append(c);
      }
    }
    throw error("Unterminated string");
  }

  private <T> void parseValue(JSONObserver<T> target, String key, int depth) {
    skipWhitespace();
    if (pos >= len) throw error("Unexpected end of input");

    path.push(key);
    try {
      char c = src.charAt(pos);
      switch (c) {
        case '"' -> target.string(key, parseString());
        case 't' -> { parseLiteral("true"); target.bool(key, true); }
        case 'f' -> { parseLiteral("false"); target.bool(key, false); }
        case 'n' -> { parseLiteral("null"); target.nullValue(key); }
        case '-' -> dispatchNumber(target, key);
        default -> {
          if (c >= '0' && c <= '9') dispatchNumber(target, key);
          else if (c == '{' || c == '[') {
            // Containers handled in a later task — TASK 13 introduces nested object/array dispatch.
            throw error("Container values not yet implemented in this task");
          } else {
            throw error("Unexpected character [" + c + "]");
          }
        }
      }
    } finally {
      path.pop();
    }
  }

  private <T> void dispatchNumber(JSONObserver<T> target, String key) {
    Number n = parseNumber();
    if (n instanceof Long l) target.integer(key, l);
    else if (n instanceof BigInteger bi) target.bigInteger(key, bi);
    else target.decimal(key, (BigDecimal) n);
  }

  private String pathString() {
    var sb = new StringBuilder("$");
    var it = path.descendingIterator();
    while (it.hasNext()) {
      sb.append('.').append(it.next());
    }
    return sb.toString();
  }

  private char peek() {
    return src.charAt(pos);
  }

  private void skipWhitespace() {
    while (pos < len) {
      char c = src.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
      else break;
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `latte test --test=JSONParserScalarsTest`
Expected: PASS, 12 tests run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONParser.java \
        src/test/java/org/lattejava/json/tests/JSONParserScalarsTest.java
git commit -m "feat: add JSONParser with scalar callbacks and JSON-path tracking"
```

---

## Task 13: `JSONParser` — nested objects and arrays

Extend the parser to dispatch into `beginObject` / `beginArray` for container values, drive the child observer, and call back through `object` / `array` with the child's `finish()` result.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONParser.java` (replace the "Container values not yet implemented" stub)
- Test: `src/test/java/org/lattejava/json/tests/JSONParserContainersTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/JSONParserContainersTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserContainersTest {

  @Test
  public void parsesNestedObjectViaAnyObjectObserver() {
    var obs = new AnyObjectObserver();
    new JSONParser().parse("{\"a\":1,\"nested\":{\"b\":2}}", obs);
    Map<String, Object> result = obs.finish();
    assertEquals(result.get("a"), 1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) result.get("nested");
    assertEquals(nested.get("b"), 2L);
  }

  @Test
  public void parsesArrayOfScalars() {
    var obs = new AnyObjectObserver();
    new JSONParser().parse("{\"xs\":[1,2,3]}", obs);
    @SuppressWarnings("unchecked")
    List<Object> xs = (List<Object>) obs.finish().get("xs");
    assertEquals(xs, List.of(1L, 2L, 3L));
  }

  @Test
  public void parsesArrayOfObjects() {
    var obs = new AnyObjectObserver();
    new JSONParser().parse("{\"items\":[{\"k\":1},{\"k\":2}]}", obs);
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) obs.finish().get("items");
    assertEquals(items.size(), 2);
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) items.get(0);
    assertEquals(first.get("k"), 1L);
  }

  @Test
  public void parsesNestedArrays() {
    var obs = new AnyObjectObserver();
    new JSONParser().parse("{\"xs\":[[1,2],[3,4]]}", obs);
    @SuppressWarnings("unchecked")
    List<Object> xs = (List<Object>) obs.finish().get("xs");
    @SuppressWarnings("unchecked")
    List<Object> inner = (List<Object>) xs.get(1);
    assertEquals(inner, List.of(3L, 4L));
  }

  @Test
  public void emptyArrayParses() {
    var obs = new AnyObjectObserver();
    new JSONParser().parse("{\"xs\":[]}", obs);
    @SuppressWarnings("unchecked")
    List<Object> xs = (List<Object>) obs.finish().get("xs");
    assertTrue(xs.isEmpty());
  }

  @Test
  public void emptyNestedObjectParses() {
    var obs = new AnyObjectObserver();
    new JSONParser().parse("{\"o\":{}}", obs);
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) obs.finish().get("o");
    assertTrue(nested.isEmpty());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=JSONParserContainersTest`
Expected: FAIL — the current parser throws "Container values not yet implemented" for `{` and `[`.

- [ ] **Step 3: Replace the container stub in `JSONParser.java`**

In `parseValue`, replace this block:

```java
else if (c == '{' || c == '[') {
  // Containers handled in a later task — TASK 13 introduces nested object/array dispatch.
  throw error("Container values not yet implemented in this task");
}
```

with this block:

```java
else if (c == '{') {
  @SuppressWarnings("unchecked")
  JSONObserver<Object> child = (JSONObserver<Object>) target.beginObject(key);
  parseObjectInto(child, depth + 1);
  Object value = child.finish();
  target.object(key, value);
}
else if (c == '[') {
  @SuppressWarnings("unchecked")
  JSONArrayObserver<Object> child = (JSONArrayObserver<Object>) target.beginArray(key);
  parseArrayInto(child, depth + 1);
  Object value = child.finish();
  target.array(key, value);
}
```

And add two new private methods after `parseObjectInto`:

```java
private <T> void parseArrayInto(JSONArrayObserver<T> target, int depth) {
  if (depth > maxNestingDepth) {
    throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
  }
  expect('[');
  skipWhitespace();
  if (pos < len && src.charAt(pos) == ']') {
    pos++;
    return;
  }
  int index = 0;
  while (true) {
    parseArrayValue(target, index, depth);
    skipWhitespace();
    if (pos >= len) throw error("Unterminated array");
    char nc = src.charAt(pos);
    if (nc == ',') { pos++; index++; continue; }
    if (nc == ']') { pos++; return; }
    throw error("Expected [,] or []] but found [" + nc + "]");
  }
}

private <T> void parseArrayValue(JSONArrayObserver<T> target, int index, int depth) {
  skipWhitespace();
  if (pos >= len) throw error("Unexpected end of input");

  path.push("[" + index + "]");
  try {
    char c = src.charAt(pos);
    switch (c) {
      case '"' -> target.string(parseString());
      case 't' -> { parseLiteral("true"); target.bool(true); }
      case 'f' -> { parseLiteral("false"); target.bool(false); }
      case 'n' -> { parseLiteral("null"); target.nullValue(); }
      case '-' -> dispatchArrayNumber(target);
      case '{' -> {
        @SuppressWarnings("unchecked")
        JSONObserver<Object> child = (JSONObserver<Object>) target.beginObject();
        parseObjectInto(child, depth + 1);
        target.object(child.finish());
      }
      case '[' -> {
        @SuppressWarnings("unchecked")
        JSONArrayObserver<Object> child = (JSONArrayObserver<Object>) target.beginArray();
        parseArrayInto(child, depth + 1);
        target.array(child.finish());
      }
      default -> {
        if (c >= '0' && c <= '9') dispatchArrayNumber(target);
        else throw error("Unexpected character [" + c + "]");
      }
    }
  } finally {
    path.pop();
  }
}

private <T> void dispatchArrayNumber(JSONArrayObserver<T> target) {
  Number n = parseNumber();
  if (n instanceof Long l) target.integer(l);
  else if (n instanceof BigInteger bi) target.bigInteger(bi);
  else target.decimal((BigDecimal) n);
}
```

The path entry for array elements uses the form `[index]` so the rendered path reads as `$.items[2]` rather than `$.items.[2]`. Verify in the next test that the path renders correctly.

- [ ] **Step 4: Fix `pathString()` so array brackets concatenate without a preceding dot**

Replace `pathString()` with:

```java
private String pathString() {
  var sb = new StringBuilder("$");
  var it = path.descendingIterator();
  while (it.hasNext()) {
    String segment = it.next();
    if (segment.startsWith("[")) {
      sb.append(segment);
    } else {
      sb.append('.').append(segment);
    }
  }
  return sb.toString();
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `latte test --test=JSONParserContainersTest`
Expected: PASS, 6 tests run.

Also run the prior scalars test to verify no regression:

Run: `latte test --test=JSONParserScalarsTest`
Expected: PASS, 12 tests run.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONParser.java \
        src/test/java/org/lattejava/json/tests/JSONParserContainersTest.java
git commit -m "feat: parser dispatches nested objects and arrays through observers"
```

---

## Task 14: `JSONParser` — error path coverage

Verify that error messages include the JSON-path and that the parser rejects malformed input at every defined error site.

**Files:**
- Test: `src/test/java/org/lattejava/json/tests/JSONParserErrorsTest.java`

- [ ] **Step 1: Write the test**

`src/test/java/org/lattejava/json/tests/JSONParserErrorsTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserErrorsTest {

  static JSONProcessingException parseAndCatch(String json) {
    try {
      new JSONParser().parse(json, new AnyObjectObserver());
      throw new AssertionError("Expected JSONProcessingException for input [" + json + "]");
    } catch (JSONProcessingException e) {
      return e;
    }
  }

  @Test
  public void topLevelArrayRejected() {
    var e = parseAndCatch("[1,2,3]");
    assertTrue(e.getMessage().contains("top-level JSON object"));
  }

  @Test
  public void topLevelStringRejected() {
    var e = parseAndCatch("\"hi\"");
    assertTrue(e.getMessage().contains("top-level JSON object"));
  }

  @Test
  public void emptyInputRejected() {
    var e = parseAndCatch("");
    assertTrue(e.getMessage().contains("Empty input"));
  }

  @Test
  public void trailingContentRejected() {
    var e = parseAndCatch("{}garbage");
    assertTrue(e.getMessage().contains("Trailing content"));
  }

  @Test
  public void pathRecordedForNestedScalar() {
    var e = parseAndCatch("{\"a\":{\"b\":@}}");
    assertTrue(e.getMessage().contains("[$.a.b]"),
        "Expected path [$.a.b] in message but was: " + e.getMessage());
  }

  @Test
  public void pathRecordedForArrayIndex() {
    var e = parseAndCatch("{\"xs\":[1,@]}");
    assertTrue(e.getMessage().contains("[$.xs[1]]"),
        "Expected path [$.xs[1]] in message but was: " + e.getMessage());
  }

  @Test
  public void unterminatedStringRejected() {
    var e = parseAndCatch("{\"s\":\"hello");
    assertTrue(e.getMessage().contains("Unterminated string"));
  }

  @Test
  public void invalidEscapeRejected() {
    var e = parseAndCatch("{\"s\":\"\\x\"}");
    assertTrue(e.getMessage().contains("Invalid escape"));
  }

  @Test
  public void truncatedUnicodeEscapeRejected() {
    var e = parseAndCatch("{\"s\":\"\\u00\"}");
    assertTrue(e.getMessage().contains("Truncated") || e.getMessage().contains("Invalid"));
  }

  @Test
  public void loneHighSurrogateRejected() {
    var e = parseAndCatch("{\"s\":\"\\uD83D\"}");
    assertTrue(e.getMessage().contains("Lone high surrogate"));
  }

  @Test
  public void numberDotWithoutFractionRejected() {
    var e = parseAndCatch("{\"d\":1.}");
    assertTrue(e.getMessage().contains("fractional"));
  }

  @Test
  public void numberExponentWithoutDigitsRejected() {
    var e = parseAndCatch("{\"d\":1e}");
    assertTrue(e.getMessage().contains("exponent"));
  }

  @Test
  public void unterminatedObjectRejected() {
    var e = parseAndCatch("{\"a\":1");
    assertTrue(e.getMessage().contains("Unterminated") || e.getMessage().contains("Expected"));
  }

  @Test
  public void unterminatedArrayRejected() {
    var e = parseAndCatch("{\"xs\":[1,2");
    assertTrue(e.getMessage().contains("Unterminated") || e.getMessage().contains("Expected"));
  }

  @Test
  public void nullInputRejected() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parse((String) null, new AnyObjectObserver()));
    assertTrue(e.getMessage().contains("Input string is null"));
  }

  @Test
  public void nullBytesRejected() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parse((byte[]) null, new AnyObjectObserver()));
    assertTrue(e.getMessage().contains("Input bytes are null"));
  }

  @Test
  public void nullObserverRejected() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parse("{}", null));
    assertTrue(e.getMessage().contains("Observer is null"));
  }

  @Test
  public void maxNestingDepthEnforced() {
    // Construct a deeply nested object exceeding the default depth of 64.
    StringBuilder open = new StringBuilder();
    StringBuilder close = new StringBuilder();
    for (int i = 0; i < 70; i++) {
      open.append("{\"x\":");
      close.append("}");
    }
    open.append("1");
    var e = parseAndCatch(open.append(close).toString());
    assertTrue(e.getMessage().contains("Maximum nesting depth"));
  }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `latte test --test=JSONParserErrorsTest`
Expected: PASS, 18 tests run.

If any test fails, the parser is missing a corresponding error check — add it.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/json/tests/JSONParserErrorsTest.java
git commit -m "test: cover JSONParser error paths and JSON-path messages"
```

---

## Task 15: `JSONParser` — polymorphic dispatch (two-pass scan-ahead)

The parser detects when a parent's `beginObject(key)` returns a `JSONPolymorphicObserver`. It saves the current position, scans the object's keys looking for the discriminator, rewinds, calls `observerFor(discriminatorValue)`, and parses normally — skipping the discriminator key during parse since it doesn't map to a subtype field.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONParser.java`
- Test: `src/test/java/org/lattejava/json/tests/JSONParserPolymorphismTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/JSONParserPolymorphismTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserPolymorphismTest {

  /** Hand-written polymorphic observer simulating what codegen will emit for a sealed Pet/Dog/Cat. */
  static final class PetPoly implements JSONPolymorphicObserver<Object> {
    @Override public String discriminatorKey() { return "petType"; }
    @Override public JSONObserver<?> observerFor(String value) {
      return switch (value) {
        case "Dog" -> new RecordingChild("Dog");
        case "Cat" -> new RecordingChild("Cat");
        default -> throw new JSONProcessingException(
            "Unknown discriminator value [" + value + "] for [petType]");
      };
    }
  }

  /** Concrete subtype observer that records its key/value pairs without expecting a discriminator key. */
  static final class RecordingChild implements JSONObserver<Map<String, Object>> {
    final String typeName;
    final Map<String, Object> data = new LinkedHashMap<>();
    RecordingChild(String typeName) { this.typeName = typeName; }

    @Override public JSONArrayObserver<?> beginArray(String key) { return new AnyArrayObserver(); }
    @Override public JSONObserver<?> beginObject(String key) { return new AnyObjectObserver(); }
    @Override public void bigInteger(String key, BigInteger value) { data.put(key, value); }
    @Override public void bool(String key, boolean value) { data.put(key, value); }
    @Override public void decimal(String key, BigDecimal value) { data.put(key, value); }
    @Override public Map<String, Object> finish() { data.put("__type", typeName); return data; }
    @Override public void integer(String key, long value) { data.put(key, value); }
    @Override public void nullValue(String key) { data.put(key, null); }
    @Override public void object(String key, Object value) { data.put(key, value); }
    @Override public void string(String key, String value) { data.put(key, value); }
    @Override public void array(String key, Object value) { data.put(key, value); }
  }

  /** Parent observer that returns the polymorphic observer for a single key. */
  static final class Parent implements JSONObserver<Map<String, Object>> {
    final Map<String, Object> data = new LinkedHashMap<>();
    @Override public JSONArrayObserver<?> beginArray(String key) { throw new AssertionError(); }
    @Override public JSONObserver<?> beginObject(String key) {
      return "pet".equals(key) ? new PetPoly() : SkipObserver.INSTANCE;
    }
    @Override public void bigInteger(String key, BigInteger value) {}
    @Override public void bool(String key, boolean value) {}
    @Override public void decimal(String key, BigDecimal value) {}
    @Override public Map<String, Object> finish() { return data; }
    @Override public void integer(String key, long value) {}
    @Override public void nullValue(String key) {}
    @Override public void object(String key, Object value) { data.put(key, value); }
    @Override public void string(String key, String value) {}
    @Override public void array(String key, Object value) {}
  }

  @Test
  public void discriminatorFirstDispatchesToDog() {
    var parent = new Parent();
    new JSONParser().parse(
        "{\"pet\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}}", parent);
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) parent.finish().get("pet");
    assertEquals(pet.get("__type"), "Dog");
    assertEquals(pet.get("name"), "Rex");
    assertEquals(pet.get("packSize"), 3L);
    assertFalse(pet.containsKey("petType"), "discriminator key must not be delivered as a field callback");
  }

  @Test
  public void discriminatorLastAlsoDispatches() {
    var parent = new Parent();
    new JSONParser().parse(
        "{\"pet\":{\"name\":\"Whiskers\",\"lives\":9,\"petType\":\"Cat\"}}", parent);
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) parent.finish().get("pet");
    assertEquals(pet.get("__type"), "Cat");
    assertEquals(pet.get("name"), "Whiskers");
    assertEquals(pet.get("lives"), 9L);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*Unknown discriminator value \\[Bird\\].*")
  public void unknownDiscriminatorValueThrows() {
    new JSONParser().parse(
        "{\"pet\":{\"petType\":\"Bird\",\"name\":\"Tweety\"}}", new Parent());
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*Discriminator key \\[petType\\] missing.*")
  public void missingDiscriminatorThrows() {
    new JSONParser().parse(
        "{\"pet\":{\"name\":\"Anonymous\"}}", new Parent());
  }

  @Test
  public void discriminatorInsideNestedObjectIsIgnored() {
    // The scan-ahead must respect nesting — a "petType" key inside a sub-object shouldn't be
    // mistaken for the outer discriminator.
    var parent = new Parent();
    new JSONParser().parse(
        "{\"pet\":{\"meta\":{\"petType\":\"InnerNoise\"},\"petType\":\"Dog\",\"name\":\"Rex\"}}",
        parent);
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) parent.finish().get("pet");
    assertEquals(pet.get("__type"), "Dog");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=JSONParserPolymorphismTest`
Expected: FAIL — the parser doesn't handle `JSONPolymorphicObserver` yet; tests that pass a polymorphic parent will get a `ClassCastException` or similar.

- [ ] **Step 3: Add polymorphic dispatch to `JSONParser`**

Replace the `{` branch in `parseValue` (currently does plain `parseObjectInto`) with:

```java
else if (c == '{') {
  Object childRaw = target.beginObject(key);
  if (childRaw instanceof JSONPolymorphicObserver<?> poly) {
    Object childResult = parsePolymorphicObject(poly, depth + 1);
    target.object(key, childResult);
  } else {
    @SuppressWarnings("unchecked")
    JSONObserver<Object> child = (JSONObserver<Object>) childRaw;
    parseObjectInto(child, depth + 1);
    target.object(key, child.finish());
  }
}
```

And the analogous change in `parseArrayValue` for the `{` branch:

```java
case '{' -> {
  Object childRaw = target.beginObject();
  if (childRaw instanceof JSONPolymorphicObserver<?> poly) {
    Object childResult = parsePolymorphicObject(poly, depth + 1);
    target.object(childResult);
  } else {
    @SuppressWarnings("unchecked")
    JSONObserver<Object> child = (JSONObserver<Object>) childRaw;
    parseObjectInto(child, depth + 1);
    target.object(child.finish());
  }
}
```

Add the polymorphic-handling method:

```java
private Object parsePolymorphicObject(JSONPolymorphicObserver<?> poly, int depth) {
  if (depth > maxNestingDepth) {
    throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
  }
  // pos currently sits at '{' — save and scan ahead for the discriminator
  if (src.charAt(pos) != '{') {
    throw error("Expected [{] for polymorphic object");
  }
  int saved = pos;
  String discriminatorKey = poly.discriminatorKey();
  String discriminatorValue = scanForDiscriminator(discriminatorKey);
  if (discriminatorValue == null) {
    throw error("Discriminator key [" + discriminatorKey + "] missing");
  }
  pos = saved;

  @SuppressWarnings("unchecked")
  JSONObserver<Object> child = (JSONObserver<Object>) poly.observerFor(discriminatorValue);

  // Parse the object body, skipping the discriminator key entries.
  parseObjectIntoSkippingKey(child, discriminatorKey, depth);
  return child.finish();
}

/** Scan-ahead from the current '{' looking for the named key at this object level. */
private String scanForDiscriminator(String discriminatorKey) {
  // Mini-parser: tracks brace depth, recognizes strings (with escapes), and skips other tokens.
  int p = pos;
  if (src.charAt(p) != '{') throw error("Scan-ahead expected [{]");
  p++;
  int braceDepth = 1;
  int bracketDepth = 0;

  while (p < len && braceDepth > 0) {
    char c = src.charAt(p);
    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { p++; continue; }

    if (braceDepth == 1 && bracketDepth == 0 && c == '"') {
      // Possible key — at object level only.
      int keyStart = p;
      String key = scanString(p);
      p = keyStart + scanStringLength(keyStart);
      // skip whitespace
      while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                      || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
      if (p >= len || src.charAt(p) != ':') throw error("Scan-ahead expected [:] after key");
      p++;
      while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                      || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
      if (key.equals(discriminatorKey)) {
        if (p >= len || src.charAt(p) != '"') {
          throw error("Discriminator value for [" + discriminatorKey + "] must be a string");
        }
        return scanString(p);
      }
      // Skip the value structurally.
      p = skipValueAt(p);
      // Skip optional comma
      while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                      || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
      if (p < len && src.charAt(p) == ',') p++;
      continue;
    }

    if (c == '{')      braceDepth++;
    else if (c == '}') braceDepth--;
    else if (c == '[') bracketDepth++;
    else if (c == ']') bracketDepth--;
    else if (c == '"') {
      // String inside nested structure — skip it without parsing as a key.
      p = keyEndOfString(p);
      continue;
    }
    p++;
  }
  return null;
}

/** Scan from pos `p` (which must point at `"`) and return the decoded string. */
private String scanString(int p) {
  if (src.charAt(p) != '"') throw error("Scan expected [\"]");
  int q = p + 1;
  StringBuilder sb = new StringBuilder();
  while (q < len) {
    char c = src.charAt(q++);
    if (c == '"') return sb.toString();
    if (c == '\\') {
      if (q >= len) throw error("Scan-ahead unterminated escape");
      char esc = src.charAt(q++);
      switch (esc) {
        case '"' -> sb.append('"');
        case '\\' -> sb.append('\\');
        case '/' -> sb.append('/');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (q + 4 > len) throw error("Scan-ahead truncated \\u escape");
          int code = Integer.parseInt(src, q, q + 4, 16);
          q += 4;
          sb.append((char) code);
        }
        default -> throw error("Scan-ahead invalid escape [\\" + esc + "]");
      }
    } else {
      sb.append(c);
    }
  }
  throw error("Scan-ahead unterminated string");
}

/** Length (including both quotes) of the JSON string starting at `p`. */
private int scanStringLength(int p) {
  return keyEndOfString(p) - p;
}

/** Returns the position just past the closing `"` of the JSON string starting at `p`. */
private int keyEndOfString(int p) {
  if (src.charAt(p) != '"') throw error("Scan expected [\"]");
  int q = p + 1;
  while (q < len) {
    char c = src.charAt(q++);
    if (c == '"') return q;
    if (c == '\\') {
      if (q >= len) throw error("Unterminated escape in scan-ahead");
      q++;
    }
  }
  throw error("Unterminated string in scan-ahead");
}

/** Step over a JSON value starting at `p` and return the position just past it. */
private int skipValueAt(int p) {
  while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                  || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
  if (p >= len) throw error("Unexpected end during scan-ahead");
  char c = src.charAt(p);
  return switch (c) {
    case '"' -> keyEndOfString(p);
    case '{' -> skipContainerAt(p, '{', '}');
    case '[' -> skipContainerAt(p, '[', ']');
    case 't' -> p + 4;
    case 'f' -> p + 5;
    case 'n' -> p + 4;
    default -> skipNumberAt(p);
  };
}

private int skipContainerAt(int p, char open, char close) {
  int depth = 0;
  while (p < len) {
    char c = src.charAt(p);
    if (c == '"') { p = keyEndOfString(p); continue; }
    if (c == open) depth++;
    else if (c == close) {
      depth--;
      if (depth == 0) return p + 1;
    }
    p++;
  }
  throw error("Unterminated container in scan-ahead");
}

private int skipNumberAt(int p) {
  if (src.charAt(p) == '-') p++;
  while (p < len) {
    char c = src.charAt(p);
    if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') p++;
    else break;
  }
  return p;
}

/** Parse the object body driving `target`, dropping any entry whose key equals `skip`. */
private <T> void parseObjectIntoSkippingKey(JSONObserver<T> target, String skip, int depth) {
  if (depth > maxNestingDepth) {
    throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
  }
  expect('{');
  skipWhitespace();
  if (pos < len && src.charAt(pos) == '}') {
    pos++;
    return;
  }
  while (true) {
    skipWhitespace();
    if (pos >= len || src.charAt(pos) != '"') throw error("Expected string key");
    String key = parseString();
    skipWhitespace();
    expect(':');
    if (key.equals(skip)) {
      skipWhitespace();
      pos = skipValueAt(pos);
    } else {
      parseValue(target, key, depth);
    }
    skipWhitespace();
    if (pos >= len) throw error("Unterminated object");
    char nc = src.charAt(pos);
    if (nc == ',') { pos++; continue; }
    if (nc == '}') { pos++; return; }
    throw error("Expected [,] or [}] but found [" + nc + "]");
  }
}
```

- [ ] **Step 4: Add a top-level polymorphic entry point on the parser**

Generated code for sealed-type root deserialization (e.g., `PetJSON.fromJSON(json)`) calls a parser method that accepts a `JSONPolymorphicObserver` directly rather than wrapping it in a parent observer. Add this method to `JSONParser`:

```java
public <T> T parsePolymorphic(String json, JSONPolymorphicObserver<T> target) {
  if (json == null) {
    throw new JSONProcessingException("Input string is null");
  }
  if (target == null) {
    throw new JSONProcessingException("Observer is null");
  }
  this.src = json;
  this.len = json.length();
  this.pos = 0;
  this.path.clear();

  skipWhitespace();
  if (pos >= len) {
    throw error("Empty input");
  }
  if (peek() != '{') {
    throw error("Expected top-level JSON object but found [" + peek() + "]");
  }
  @SuppressWarnings("unchecked")
  T result = (T) parsePolymorphicObject(target, 0);
  skipWhitespace();
  if (pos != len) {
    throw error("Trailing content after JSON value");
  }
  return result;
}

public <T> T parsePolymorphic(byte[] bytes, JSONPolymorphicObserver<T> target) {
  if (bytes == null) {
    throw new JSONProcessingException("Input bytes are null");
  }
  return parsePolymorphic(new String(bytes, StandardCharsets.UTF_8), target);
}
```

Then append the following tests to `JSONParserPolymorphismTest`:

```java
  @Test
  public void parsePolymorphicAtRootDispatchesToDog() {
    Object result = new JSONParser().parsePolymorphic(
        "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}", new PetPoly());
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) result;
    assertEquals(pet.get("__type"), "Dog");
    assertEquals(pet.get("name"), "Rex");
    assertEquals(pet.get("packSize"), 3L);
  }

  @Test
  public void parsePolymorphicAtRootDispatchesToCatWithDiscriminatorLast() {
    Object result = new JSONParser().parsePolymorphic(
        "{\"name\":\"Whiskers\",\"lives\":9,\"petType\":\"Cat\"}", new PetPoly());
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) result;
    assertEquals(pet.get("__type"), "Cat");
  }

  @Test
  public void parsePolymorphicRejectsTopLevelArray() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parsePolymorphic("[1,2,3]", new PetPoly()));
    assertTrue(e.getMessage().contains("top-level JSON object"));
  }

  @Test
  public void parsePolymorphicFromBytes() {
    byte[] bytes = "{\"petType\":\"Dog\",\"name\":\"Rex\"}"
        .getBytes(StandardCharsets.UTF_8);
    Object result = new JSONParser().parsePolymorphic(bytes, new PetPoly());
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) result;
    assertEquals(pet.get("__type"), "Dog");
  }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `latte test --test=JSONParserPolymorphismTest`
Expected: PASS, 9 tests run.

Also run the prior parser tests to verify no regression:

Run: `latte test --test=JSONParserScalarsTest`
Run: `latte test --test=JSONParserContainersTest`
Run: `latte test --test=JSONParserErrorsTest`

All expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONParser.java \
        src/test/java/org/lattejava/json/tests/JSONParserPolymorphismTest.java
git commit -m "feat: parser handles JSONPolymorphicObserver via two-pass scan-ahead"
```

---

## Task 16: Full-suite smoke

Run the entire test suite to confirm no inter-class regressions.

- [ ] **Step 1: Run the full suite**

Run: `latte test`
Expected: PASS. All tests in `org.lattejava.json.tests` should run and pass.

- [ ] **Step 2: Verify a clean rebuild succeeds**

Run: `latte clean && latte build && latte test`
Expected: PASS.

- [ ] **Step 3: Commit nothing — this is a verification gate**

If everything is green, plan 1 is complete.

If anything fails, surface the failure to the human reviewer rather than attempting silent fixes.

---

## Plan summary

After all 16 tasks are complete, the library has:

- Six annotations covering every codegen-time concern (`@JSON`, `@JSONField`, `@JSONTypeInfo`, `@JSONSubtype`, `@JSONConstructor`, `@JSONCatchAll`).
- `NamingStrategy` enum (consumed by the processor in plan 5).
- `JSONProcessingException` — single exception type for parser and builder failures.
- `JSONObserver<T>`, `JSONArrayObserver<T>`, `JSONPolymorphicObserver<T>` — the full observer protocol.
- `JSONParser` — observer-driven parser with JSON-path tracking, scalar/container support, and polymorphic dispatch via two-pass scan-ahead.
- `JSONBuilder` — fluent writer with omit-nulls default.
- `SkipObserver` / `SkipArrayObserver` — stateless singletons for lenient unknown-key handling.
- `AnyObjectObserver` / `AnyArrayObserver` — per-instance accumulators for catch-all use.
- `Numbers.toByteExact` / `Numbers.toShortExact` — range-checked narrowing helpers.

Plan 2 ("Processor + simple records") will add the annotation processor itself, helper-source-emission infrastructure (resources + package rewrite), and codegen for records with primitives, boxed primitives, and `String` fields. The helpers built in plan 1 will be packaged as resources in the processor JAR at that point.
