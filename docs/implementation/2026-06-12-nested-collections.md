# Nested Collections via Type Plans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support arbitrarily nested collection member types (`Map<String, List<Domain>>`, `Map<K, Set<E>>`, `List<List<E>>`, `Map<K, Map<K2, V>>`, any depth) by replacing the flat per-member collection codegen with generic, compile-checked **type plans** interpreted by reusable runtime observers.

**Architecture:** Three new runtime helpers — `JSONPlan` (generic `Node<T>` descriptor tree + recursive serialize walker), `JSONPlanMapObserver`, `JSONPlanArrayObserver` — carry the recursion once; per-member generated code shrinks to one static plan built from typed factories and method references (cast-free). All collection members (including existing one-level ones) migrate onto plans; the typed `<Name>MapObserver`/`<Name>ArrayObserver` inner-class codegen is deleted. Validation becomes a recursive type-tree walk. Dynamic maps (`Map<String, Object>`) and `@JSONCatchAll` keep their existing paths.

**Tech Stack:** Java 25 annotation processor, JTE 3.2.1 templates (precompiled via `org.lattejava.json.jte.Generate`, see `project.latte:47-53`), TestNG fixture tests compiled with real `javac` via `ProcessorHarness`.

**Design spec:** `docs/design/2026-06-12-nested-collections-design.md` (Status: approved).

---

## Background the implementer needs

- **Companion generation.** Each `@JSON` type `Foo` generates `<pkg>.internal.FooJSON` from `src/main/jte/companion.jte` + partials. Templates read facts from `CompanionView`/`Component`/`TypeView` (in `src/main/java/org/lattejava/json/jte/`). `CompanionWriter` renders via `JTEEngine.render("companion.jte", ...)`.
- **Runtime helpers** live as canonical sources in `src/main/java/org/lattejava/json/` and are (a) listed in `HelperEmitter.HELPERS` (`src/main/java/org/lattejava/json/processor/HelperEmitter.java:12`) for per-module emission into `<module>.internal`, and (b) copied into the jar's resource dir by `project.latte:58-62` using filename globs — `/JSON.*/` matches the three new `JSONPlan*` helpers automatically. **No `project.latte` change.**
- **Parser contract** (push-based): `JSONObserver<T>` (keyed callbacks: `string(key, v)`, `integer(key, long)`, `bigInteger`, `decimal`, `bool`, `nullValue(key)`, `beginObject(key) → JSONObjectHandler`, `beginArray(key) → JSONArrayObserver<?>`, `object(key, Object)`/`array(key, Object)` completion callbacks, `finish() → T`). `JSONArrayObserver<T>` is the positional mirror. `JSONObjectHandler` is sealed permitting `JSONObserver` and `JSONPolymorphicObserver`. `JSONParser.parse(String|byte[], JSONObserver<T>)` drives a root object.
- **Builders:** `JSONBuilder(boolean omitNulls)` — keyed `string/bool/integer(key, long|Number)/bigInteger/decimal(BigDecimal|Double|Float)/object(key, rawJson)/array(key, rawJson)/nullValue`; null boxed values and null raw JSON go through `omitNulls`. `JSONArrayBuilder(boolean omitNulls)` (no-arg = `true`) — positional, `raw(null)` always writes `null` (array elements are never omitted).
- **Current flat codegen being replaced:** `arrayObserver.jte` (per-member `<name>ToJSON` + `<Name>ArrayObserver`), `mapObserver.jte` (per-member `<name>ToJSON` + `<Name>MapObserver`, plus the **dynamic-map** branch that must survive). Scalar conversion expressions live in `fromString.jte` (string-form reads), `narrow.jte` (numeric reads from `integer`/`bigInteger`/`decimal` sources, expressions over a variable named `value`), `arrayAppend.jte` (positional writes over an expression), `memberCall.jte` (keyed writes). **The plan templates reuse these four partials verbatim inside generated lambdas so scalar fidelity is automatic.**
- **Dynamic maps:** `TypeView.isDynamicMap()` = direct `Map<String, Object>`; routed to `AnyObjectObserver` + an `any`-based serializer; `CompanionView.hasDynamicMap()` gates `Any*Observer` imports. These paths are untouched.
- **`collectEnums`** (`CompanionWriter`) is already fully recursive over Map keys/values and List/Set elements — nested enum leaves get their imports with no change.
- **Build/test:** `latte build`, `latte test`, `latte test --test=<ClassName>`. Java 25 required. Suite currently ~283 tests, all green.
- **Conventions** (`.claude/rules/`): SPDX header on every non-fixture Java file; fixtures under `src/test/resources/fixtures/` have NO header; uppercase acronyms; runtime values in `[brackets]` in error messages; `import module java.base;`; alphabetized members within visibility groups; in-class order static fields → instance fields → ctors → static methods → instance methods.

## File structure

**New runtime (canonical sources + emitted helpers):**
- `src/main/java/org/lattejava/json/JSONPlan.java` — sealed generic `Node<T>` model, factories, recursive serialize walker, `typeName` describer
- `src/main/java/org/lattejava/json/JSONPlanArrayObserver.java` — generic array interpreter (List/Set nodes)
- `src/main/java/org/lattejava/json/JSONPlanMapObserver.java` — generic map interpreter (Map nodes)

**Modified processor/views:** `HelperEmitter.java` (HELPERS list), `TypeView.java` (recursive `decl()`), `AbstractValidator.java` (recursive collection validation).

**Templates:** new `plan.jte` (per-member plan field or dynamic-map serializer), `planNode.jte` (recursive node expression), `planLeaf.jte` (scalar leaf expression), `planKeyReader.jte`/`planKeyWriter.jte` (map key lambdas); modified `companion.jte`, `observerBody.jte`, `declType.jte`; **deleted** `arrayObserver.jte`, `mapObserver.jte`, `cap.jte` (unused after migration).

**Tests:** new `JSONPlanTest.java`, `JSONPlanObserverTest.java` (runtime unit tests); new fixture sets `deepcollections/` + `DeepCollectionsCodegenTest.java`, `baddeepcollections/` + `DeepCollectionsRejectionTest.java`; restructured `badcollections/` + `CollectionRejectionTest.java`.

---

### Task 1: `JSONPlan` — node model, factories, serialize walker

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONPlan.java`
- Modify: `src/main/java/org/lattejava/json/processor/HelperEmitter.java:12-16`
- Test: `src/test/java/org/lattejava/json/tests/JSONPlanTest.java`

- [ ] **Step 1: Write the failing unit test**

`src/test/java/org/lattejava/json/tests/JSONPlanTest.java` (test package for runtime classes, like `JSONBuilderTest`):

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

public class JSONPlanTest {
  static JSONPlan.Node<Integer> intLeaf() {
    return JSONPlan.scalar("java.lang.Integer",
        null,
        value -> Numbers.toIntExact(value),
        value -> Numbers.toIntExact(value),
        value -> Numbers.toIntExact(value),
        false,
        (b, e) -> b.integer(e == null ? null : e.longValue()),
        (b, k, e) -> b.integer(k, e));
  }

  static JSONPlan.Node<String> stringLeaf() {
    return JSONPlan.scalar("java.lang.String",
        value -> value, null, null, null, false,
        (b, e) -> b.string(e),
        (b, k, e) -> b.string(k, e));
  }

  @Test
  public void writesMapOfListOfInts() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(intLeaf()));
    Map<String, List<Integer>> v = new LinkedHashMap<>();
    v.put("a", List.of(1, 2));
    v.put("b", List.of());
    assertEquals(JSONPlan.write(plan, v, true), "{\"a\":[1,2],\"b\":[]}");
  }

  @Test
  public void writesListOfListOfStrings() {
    var plan = JSONPlan.list(JSONPlan.list(stringLeaf()));
    List<List<String>> v = List.of(List.of("x"), List.of("y", "z"));
    assertEquals(JSONPlan.write(plan, v, true), "[[\"x\"],[\"y\",\"z\"]]");
  }

  @Test
  public void writesMapOfMapWithObjectLeaf() {
    JSONPlan.Node<String> fake = JSONPlan.object("demo.Fake", AnyObjectObserver::new, s -> "{\"v\":\"" + s + "\"}");
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.map(k -> k, k -> k, fake));
    Map<String, Map<String, String>> v = new LinkedHashMap<>();
    v.put("outer", new LinkedHashMap<>(Map.of("inner", "s")));
    assertEquals(JSONPlan.write(plan, v, true), "{\"outer\":{\"inner\":{\"v\":\"s\"}}}");
  }

  @Test
  public void nullArrayElementsAlwaysWritten() {
    var plan = JSONPlan.list(intLeaf());
    List<Integer> v = new ArrayList<>();
    v.add(1);
    v.add(null);
    assertEquals(JSONPlan.write(plan, v, true), "[1,null]");
  }

  @Test
  public void nullMapEntriesHonorOmitNulls() {
    var plan = JSONPlan.map(k -> k, k -> k, intLeaf());
    Map<String, Integer> v = new LinkedHashMap<>();
    v.put("a", null);
    v.put("b", 2);
    assertEquals(JSONPlan.write(plan, v, true), "{\"b\":2}");
    assertEquals(JSONPlan.write(plan, v, false), "{\"a\":null,\"b\":2}");
  }

  @Test
  public void nestedMapInsideListHonorsOmitNulls() {
    var plan = JSONPlan.list(JSONPlan.map(k -> k, k -> k, intLeaf()));
    Map<String, Integer> inner = new LinkedHashMap<>();
    inner.put("a", null);
    List<Map<String, Integer>> v = List.of(inner);
    assertEquals(JSONPlan.write(plan, v, true), "[{}]");
    assertEquals(JSONPlan.write(plan, v, false), "[{\"a\":null}]");
  }

  @Test
  public void enumStyleKeyWriterApplied() {
    var plan = JSONPlan.map(k -> k, k -> "K_" + k, intLeaf());
    Map<String, Integer> v = new LinkedHashMap<>(Map.of("a", 1));
    assertEquals(JSONPlan.write(plan, v, true), "{\"K_a\":1}");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=JSONPlanTest`
Expected: FAIL — compilation error, `JSONPlan` does not exist.

- [ ] **Step 3: Implement `JSONPlan`**

`src/main/java/org/lattejava/json/JSONPlan.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Type plan for a collection-typed {@code @JSON} member: a static, generic description of the member's
 * collection type tree, built by generated code from the typed factories below and interpreted at runtime —
 * by {@link #write} on the serialize side and by {@link JSONPlanMapObserver}/{@link JSONPlanArrayObserver}
 * on the deserialize side. {@code @JSON} object leaves dispatch to their generated companions; scalar
 * leaves carry the generated conversion lambdas. Plans are immutable and shared (one static instance per
 * member); interpretation allocates per parse only.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlan {
  private JSONPlan() {
  }

  /** One node of a member's collection type tree, generic in the Java value type the node produces. */
  public sealed interface Node<T> permits ListNode, MapNode, ObjectLeaf, ScalarLeaf, SetNode {
  }

  /** Keyed scalar write into a {@link JSONBuilder} (the map-value position of a scalar leaf). */
  @FunctionalInterface
  public interface KeyedWrite<T> {
    void write(JSONBuilder builder, String key, T value);
  }

  public record ListNode<E>(Node<E> child) implements Node<List<E>> {
  }

  public record MapNode<K, V>(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child) implements Node<Map<K, V>> {
  }

  public record ObjectLeaf<T>(String typeName, Supplier<JSONObjectHandler> observer, Function<T, String> writer) implements Node<T> {
  }

  public record ScalarLeaf<T>(String typeName, Function<String, T> fromString, LongFunction<T> fromInteger,
                              Function<BigInteger, T> fromBigInteger, Function<BigDecimal, T> fromDecimal,
                              boolean acceptsBool, BiConsumer<JSONArrayBuilder, T> append, KeyedWrite<T> write) implements Node<T> {
  }

  public record SetNode<E>(Node<E> child) implements Node<Set<E>> {
  }

  public static <E> ListNode<E> list(Node<E> child) {
    return new ListNode<>(child);
  }

  public static <K, V> MapNode<K, V> map(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child) {
    return new MapNode<>(keyReader, keyWriter, child);
  }

  public static <T> Node<T> object(String typeName, Supplier<JSONObjectHandler> observer, Function<T, String> writer) {
    return new ObjectLeaf<>(typeName, observer, writer);
  }

  public static <T> Node<T> scalar(String typeName, Function<String, T> fromString, LongFunction<T> fromInteger,
                                   Function<BigInteger, T> fromBigInteger, Function<BigDecimal, T> fromDecimal,
                                   boolean acceptsBool, BiConsumer<JSONArrayBuilder, T> append, KeyedWrite<T> write) {
    return new ScalarLeaf<>(typeName, fromString, fromInteger, fromBigInteger, fromDecimal, acceptsBool, append, write);
  }

  public static <E> SetNode<E> set(Node<E> child) {
    return new SetNode<>(child);
  }

  /** The display name of {@code node}'s value type for error messages. */
  public static String typeName(Node<?> node) {
    return switch (node) {
      case ListNode<?> n -> "List<" + typeName(n.child()) + ">";
      case MapNode<?, ?> n -> "Map";
      case ObjectLeaf<?> n -> n.typeName();
      case ScalarLeaf<?> n -> n.typeName();
      case SetNode<?> n -> "Set<" + typeName(n.child()) + ">";
    };
  }

  /** Serializes {@code value} (a collection member) as raw JSON by walking {@code node}. */
  public static <T> String write(Node<T> node, T value, boolean omitNulls) {
    return switch (node) {
      case ListNode<?> n -> writeArray(n.child(), (Collection<?>) value, omitNulls);
      case MapNode<?, ?> n -> writeMap(n, (Map<?, ?>) value, omitNulls);
      case ObjectLeaf<?> n -> value == null ? null : writeObject(n, value);
      case ScalarLeaf<?> n -> throw new JSONProcessingException("Plan root for type [" + n.typeName() + "] must be a collection node");
      case SetNode<?> n -> writeArray(n.child(), (Collection<?>) value, omitNulls);
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> void appendScalar(ScalarLeaf<T> leaf, JSONArrayBuilder builder, Object value) {
    leaf.append().accept(builder, (T) value);
  }

  private static <E> String writeArray(Node<E> child, Collection<?> value, boolean omitNulls) {
    var b = new JSONArrayBuilder(omitNulls);
    for (Object e : value) {
      switch (child) {
        case ListNode<?> n -> b.raw(e == null ? null : writeArray(n.child(), (Collection<?>) e, omitNulls));
        case MapNode<?, ?> n -> b.raw(e == null ? null : writeMap(n, (Map<?, ?>) e, omitNulls));
        case ObjectLeaf<?> n -> b.raw(e == null ? null : writeObject(n, e));
        case ScalarLeaf<?> n -> appendScalar(n, b, e);
        case SetNode<?> n -> b.raw(e == null ? null : writeArray(n.child(), (Collection<?>) e, omitNulls));
      }
    }
    return b.build();
  }

  @SuppressWarnings("unchecked")
  private static <K> String writeKey(MapNode<K, ?> node, Object key) {
    return node.keyWriter().apply((K) key);
  }

  private static <K, V> String writeMap(MapNode<K, V> node, Map<?, ?> value, boolean omitNulls) {
    var b = new JSONBuilder(omitNulls);
    Node<V> child = node.child();
    for (var en : value.entrySet()) {
      String key = writeKey(node, en.getKey());
      Object v = en.getValue();
      switch (child) {
        case ListNode<?> n -> b.array(key, v == null ? null : writeArray(n.child(), (Collection<?>) v, omitNulls));
        case MapNode<?, ?> n -> b.object(key, v == null ? null : writeMap(n, (Map<?, ?>) v, omitNulls));
        case ObjectLeaf<?> n -> b.object(key, v == null ? null : writeObject(n, v));
        case ScalarLeaf<?> n -> writeScalar(n, b, key, v);
        case SetNode<?> n -> b.array(key, v == null ? null : writeArray(n.child(), (Collection<?>) v, omitNulls));
      }
    }
    return b.build();
  }

  @SuppressWarnings("unchecked")
  private static <T> String writeObject(ObjectLeaf<T> leaf, Object value) {
    return leaf.writer().apply((T) value);
  }

  @SuppressWarnings("unchecked")
  private static <T> void writeScalar(ScalarLeaf<T> leaf, JSONBuilder builder, String key, Object value) {
    leaf.write().write(builder, key, (T) value);
  }
}
```

Notes for the implementer:
- A null map entry with a scalar-leaf child goes through the leaf's keyed write lambda (e.g. `b.integer(k, null)`), which routes through the builder's `omitNulls` handling — identical to today's generated map serializers. Null entries with container/object children hit the explicit `v == null ? null : ...` guards, and `b.object(key, null)`/`b.array(key, null)` also honor `omitNulls`.
- Null **array elements** are always written (`raw(null)` → `null`), matching today.
- If the generic record patterns (`case ListNode<?> n` etc.) produce compile warnings, keep the wildcard forms shown — do not switch to `<E>`-typed patterns.

- [ ] **Step 4: Add the helpers to `HelperEmitter.HELPERS`**

In `src/main/java/org/lattejava/json/processor/HelperEmitter.java`, the list becomes (alphabetical; `JSONParser` < `JSONPlan` < `JSONPlanArrayObserver` < `JSONPlanMapObserver` < `JSONPolymorphicObserver`):

```java
  public static final List<String> HELPERS = List.of(
      "AnyArrayObserver", "AnyObjectObserver", "Conversions", "JSONArrayBuilder",
      "JSONArrayObserver", "JSONBuilder", "JSONObjectHandler", "JSONObserver",
      "JSONParser", "JSONPlan", "JSONPlanArrayObserver", "JSONPlanMapObserver",
      "JSONPolymorphicObserver", "JSONProcessingException", "Numbers",
      "SkipArrayObserver", "SkipObserver");
```

`JSONPlanArrayObserver`/`JSONPlanMapObserver` do not exist until Task 2 — to keep this task green, add only `"JSONPlan"` now and add the other two in Task 2. (The emitter reads the canonical source as a classpath resource; a missing source is a compile-time error in every fixture compilation.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=JSONPlanTest`
Expected: PASS (7 tests).

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS — adding an emitted helper must not disturb any existing fixture compilation.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONPlan.java \
        src/main/java/org/lattejava/json/processor/HelperEmitter.java \
        src/test/java/org/lattejava/json/tests/JSONPlanTest.java
git commit -m "feat: JSONPlan node model and recursive serialize walker

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `JSONPlanArrayObserver` + `JSONPlanMapObserver` — deserialize interpreters

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONPlanArrayObserver.java`
- Create: `src/main/java/org/lattejava/json/JSONPlanMapObserver.java`
- Modify: `src/main/java/org/lattejava/json/processor/HelperEmitter.java` (add the two names)
- Test: `src/test/java/org/lattejava/json/tests/JSONPlanObserverTest.java`

- [ ] **Step 1: Write the failing unit test**

A `JSONPlanMapObserver` is a full `JSONObserver`, so it can be the ROOT observer for `JSONParser.parse` — no codegen needed to test the whole interpreter chain. `src/test/java/org/lattejava/json/tests/JSONPlanObserverTest.java`:

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

public class JSONPlanObserverTest {
  static JSONPlan.Node<Integer> intLeaf() {
    return JSONPlan.scalar("java.lang.Integer",
        null,
        value -> Numbers.toIntExact(value),
        value -> Numbers.toIntExact(value),
        value -> Numbers.toIntExact(value),
        false,
        (b, e) -> b.integer(e == null ? null : e.longValue()),
        (b, k, e) -> b.integer(k, e));
  }

  static JSONPlan.Node<String> stringLeaf() {
    return JSONPlan.scalar("java.lang.String",
        value -> value, null, null, null, false,
        (b, e) -> b.string(e),
        (b, k, e) -> b.string(k, e));
  }

  @Test
  public void readsMapOfListOfInts() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(intLeaf()));
    Map<String, List<Integer>> v = new JSONParser().parse("{\"a\":[1,2],\"b\":[]}", new JSONPlanMapObserver<>(plan));
    assertEquals(v.get("a"), List.of(1, 2));
    assertTrue(v.get("b").isEmpty());
    assertEquals(new ArrayList<>(v.keySet()), List.of("a", "b"));
  }

  @Test
  public void readsMapOfMapOfStrings() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.map(k -> k, k -> k, stringLeaf()));
    Map<String, Map<String, String>> v =
        new JSONParser().parse("{\"o\":{\"i\":\"x\"}}", new JSONPlanMapObserver<>(plan));
    assertEquals(v.get("o").get("i"), "x");
    assertTrue(v.get("o") instanceof LinkedHashMap, "nested map is a LinkedHashMap");
  }

  @Test
  public void readsSetNodeIntoLinkedHashSet() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.set(stringLeaf()));
    Map<String, Set<String>> v = new JSONParser().parse("{\"s\":[\"b\",\"a\"]}", new JSONPlanMapObserver<>(plan));
    assertTrue(v.get("s") instanceof LinkedHashSet, "Set node accumulates into LinkedHashSet");
    assertEquals(new ArrayList<>(v.get("s")), List.of("b", "a"));
  }

  @Test
  public void readsObjectLeafThroughItsObserver() {
    JSONPlan.Node<Map<String, Object>> leaf =
        JSONPlan.object("demo.Fake", AnyObjectObserver::new, v -> "{}");
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(leaf));
    Map<String, List<Map<String, Object>>> v =
        new JSONParser().parse("{\"a\":[{\"x\":1}]}", new JSONPlanMapObserver<>(plan));
    assertEquals(v.get("a").getFirst().get("x"), 1L);
  }

  @Test
  public void keyReaderAppliedAtEveryLevel() {
    var plan = JSONPlan.map(k -> "outer:" + k, k -> k,
        JSONPlan.map(k -> "inner:" + k, k -> k, intLeaf()));
    Map<String, Map<String, Integer>> v =
        new JSONParser().parse("{\"a\":{\"b\":1}}", new JSONPlanMapObserver<>(plan));
    assertEquals(v.get("outer:a").get("inner:b"), 1);
  }

  @Test
  public void nullValuesLand() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(intLeaf()));
    Map<String, List<Integer>> v =
        new JSONParser().parse("{\"a\":[1,null],\"b\":null}", new JSONPlanMapObserver<>(plan));
    assertEquals(v.get("a"), Arrays.asList(1, null));
    assertTrue(v.containsKey("b") && v.get("b") == null);
  }

  @Test
  public void scalarWhereArrayExpectedThrows() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(intLeaf()));
    try {
      new JSONParser().parse("{\"a\":5}", new JSONPlanMapObserver<>(plan));
      fail("expected JSONProcessingException");
    } catch (JSONProcessingException expected) {
      assertTrue(expected.getMessage().contains("["), expected.getMessage());
    }
  }

  @Test
  public void arrayWhereScalarExpectedThrows() {
    var plan = JSONPlan.map(k -> k, k -> k, intLeaf());
    try {
      new JSONParser().parse("{\"a\":[1]}", new JSONPlanMapObserver<>(plan));
      fail("expected JSONProcessingException");
    } catch (JSONProcessingException expected) {
      assertTrue(expected.getMessage().contains("[java.lang.Integer]"), expected.getMessage());
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=JSONPlanObserverTest`
Expected: FAIL — `JSONPlanMapObserver` does not exist.

- [ ] **Step 3: Implement the two observers**

`src/main/java/org/lattejava/json/JSONPlanArrayObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * {@link JSONArrayObserver} that interprets a {@link JSONPlan.ListNode}/{@link JSONPlan.SetNode}: elements
 * are converted by the node's child (scalar leaves convert inline; object leaves dispatch to their generated
 * companion; container children recurse into a fresh plan observer). Accumulates into an {@link ArrayList}
 * or {@link LinkedHashSet} per the node kind. One instance per JSON array; not thread-safe.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlanArrayObserver<C> implements JSONArrayObserver<C> {
  private final Collection<Object> acc;
  private final JSONPlan.Node<?> child;

  private JSONPlanArrayObserver(Collection<Object> acc, JSONPlan.Node<?> child) {
    this.acc = acc;
    this.child = child;
  }

  public static <E> JSONPlanArrayObserver<List<E>> of(JSONPlan.ListNode<E> node) {
    return new JSONPlanArrayObserver<>(new ArrayList<>(), node.child());
  }

  public static <E> JSONPlanArrayObserver<Set<E>> of(JSONPlan.SetNode<E> node) {
    return new JSONPlanArrayObserver<>(new LinkedHashSet<>(), node.child());
  }

  @Override
  public void array(Object value) {
    acc.add(value);
  }

  @Override
  public JSONArrayObserver<?> beginArray() {
    return switch (child) {
      case JSONPlan.ListNode<?> n -> of(n);
      case JSONPlan.SetNode<?> n -> of(n);
      default -> throw unexpected("array");
    };
  }

  @Override
  public JSONObjectHandler beginObject() {
    return switch (child) {
      case JSONPlan.MapNode<?, ?> n -> new JSONPlanMapObserver<>(n);
      case JSONPlan.ObjectLeaf<?> n -> n.observer().get();
      default -> throw unexpected("object");
    };
  }

  @Override
  public void bigInteger(BigInteger value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromBigInteger() != null) {
      acc.add(leaf.fromBigInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void bool(boolean value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.acceptsBool()) {
      acc.add(value);
      return;
    }
    throw unexpected("boolean");
  }

  @Override
  public void decimal(BigDecimal value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromDecimal() != null) {
      acc.add(leaf.fromDecimal().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @SuppressWarnings("unchecked")
  @Override
  public C finish() {
    return (C) acc;
  }

  @Override
  public void integer(long value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromInteger() != null) {
      acc.add(leaf.fromInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void nullValue() {
    acc.add(null);
  }

  @Override
  public void object(Object value) {
    acc.add(value);
  }

  @Override
  public void string(String value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromString() != null) {
      acc.add(leaf.fromString().apply(value));
      return;
    }
    throw unexpected("string");
  }

  private JSONProcessingException unexpected(String kind) {
    return new JSONProcessingException("unexpected JSON " + kind + " for element type [" + JSONPlan.typeName(child) + "]");
  }
}
```

`src/main/java/org/lattejava/json/JSONPlanMapObserver.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * {@link JSONObserver} that interprets a {@link JSONPlan.MapNode}: keys are converted by the node's
 * {@code keyReader}; values by the node's child (scalar leaves convert inline; object leaves dispatch to
 * their generated companion; container children recurse into a fresh plan observer). Accumulates into a
 * {@link LinkedHashMap}, preserving JSON-object insertion order. One instance per JSON object; not
 * thread-safe.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlanMapObserver<K, V> implements JSONObserver<Map<K, V>> {
  private final JSONPlan.Node<?> child;
  private final Function<String, K> keyReader;
  private final Map<K, V> map = new LinkedHashMap<>();

  public JSONPlanMapObserver(JSONPlan.MapNode<K, V> node) {
    this.child = node.child();
    this.keyReader = node.keyReader();
  }

  @Override
  public void array(String key, Object value) {
    put(key, value);
  }

  @Override
  public JSONArrayObserver<?> beginArray(String key) {
    return switch (child) {
      case JSONPlan.ListNode<?> n -> JSONPlanArrayObserver.of(n);
      case JSONPlan.SetNode<?> n -> JSONPlanArrayObserver.of(n);
      default -> throw unexpected("array");
    };
  }

  @Override
  public JSONObjectHandler beginObject(String key) {
    return switch (child) {
      case JSONPlan.MapNode<?, ?> n -> new JSONPlanMapObserver<>(n);
      case JSONPlan.ObjectLeaf<?> n -> n.observer().get();
      default -> throw unexpected("object");
    };
  }

  @Override
  public void bigInteger(String key, BigInteger value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromBigInteger() != null) {
      put(key, leaf.fromBigInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void bool(String key, boolean value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.acceptsBool()) {
      put(key, value);
      return;
    }
    throw unexpected("boolean");
  }

  @Override
  public void decimal(String key, BigDecimal value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromDecimal() != null) {
      put(key, leaf.fromDecimal().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public Map<K, V> finish() {
    return map;
  }

  @Override
  public void integer(String key, long value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromInteger() != null) {
      put(key, leaf.fromInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void nullValue(String key) {
    put(key, null);
  }

  @Override
  public void object(String key, Object value) {
    put(key, value);
  }

  @Override
  public void string(String key, String value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromString() != null) {
      put(key, leaf.fromString().apply(value));
      return;
    }
    throw unexpected("string");
  }

  @SuppressWarnings("unchecked")
  private void put(String key, Object value) {
    map.put(keyReader.apply(key), (V) value);
  }

  private JSONProcessingException unexpected(String kind) {
    return new JSONProcessingException("unexpected JSON " + kind + " for Map value type [" + JSONPlan.typeName(child) + "]");
  }
}
```

- [ ] **Step 4: Add both names to `HelperEmitter.HELPERS`**

The list now reads exactly as shown in Task 1 Step 4 (with all three `JSONPlan*` entries present).

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=JSONPlanObserverTest`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONPlanArrayObserver.java \
        src/main/java/org/lattejava/json/JSONPlanMapObserver.java \
        src/main/java/org/lattejava/json/processor/HelperEmitter.java \
        src/test/java/org/lattejava/json/tests/JSONPlanObserverTest.java
git commit -m "feat: Plan-interpreting map/array observers for nested collections

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Recursive `TypeView.decl()` (declaration strings for nested generics)

`declType.jte` currently special-cases one collection level; `TypeView.decl()` returns `simpleName()` for leaves, which is broken for nested generics (`simpleName()` of `java.util.List<demo.Product>` is `"Product>"`). Make `decl()` itself recursive and reduce `declType.jte` to a delegation. Output for all currently-legal (one-level) types is identical, so the suite pins parity.

**Files:**
- Modify: `src/main/java/org/lattejava/json/jte/TypeView.java:41-43` (the `decl()` method)
- Modify: `src/main/jte/declType.jte`

- [ ] **Step 1: Make `TypeView.decl()` recursive**

Replace the current method:

```java
  /**
   * The reference form to write for this type in generated source: for a collection, the full generic
   * declaration built recursively (e.g. {@code Map<String, List<demo.Product>>}); for a type with a
   * generated companion (a nested {@code @JSON} record/class or a polymorphic {@code @JSON} interface),
   * the fully-qualified name, so no import is needed and same-simple-name collisions cannot occur; else
   * the simple name.
   */
  public String decl() {
    if (isMap()) {
      return "Map<" + key().decl() + ", " + value().decl() + ">";
    }
    if (isCollection()) {
      return kind() + "<" + element().decl() + ">";
    }
    return hasCompanion() ? name() : simpleName();
  }
```

- [ ] **Step 2: Reduce `declType.jte` to delegation**

Full new content of `src/main/jte/declType.jte`:

```jte
@param org.lattejava.json.jte.TypeView type
${type.decl()}
```

(Note: keep the template body on one line ending without a trailing newline if the current file has none — declaration strings are embedded mid-expression in generated code. Match the existing file's exact trailing-whitespace structure.)

- [ ] **Step 3: Run the full suite for parity**

Run: `latte test`
Expected: PASS — every generated declaration for one-level collections and scalars is unchanged.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/json/jte/TypeView.java src/main/jte/declType.jte
git commit -m "refactor: Recursive TypeView.decl() for nested generic declarations

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Plan templates + migrate `List`/`Set` members onto plans

Introduces the plan-emission templates and reroutes List/Set members (serialize + deserialize) through them. `arrayObserver.jte` is deleted. Map members still use `mapObserver.jte` until Task 5. This is a parity migration: the suite must stay green with no fixture changes.

**Files:**
- Create: `src/main/jte/plan.jte`, `src/main/jte/planNode.jte`, `src/main/jte/planLeaf.jte`, `src/main/jte/planKeyReader.jte`, `src/main/jte/planKeyWriter.jte`
- Modify: `src/main/jte/companion.jte` (imports, serialize call site, collection-emission loop)
- Modify: `src/main/jte/observerBody.jte:100-113` (`beginArray` routing)
- Delete: `src/main/jte/arrayObserver.jte`

- [ ] **Step 1: Create `planNode.jte` (recursive node expression)**

JTE templates compile to classes whose `@template.x` calls are plain method calls, so self-recursion works; this template is the proof (it recurses even for one-level members: the root call recurses once into the leaf).

```jte
@import org.lattejava.json.jte.TypeView
@param TypeView type
@if(type.isMap())JSONPlan.map(@template.planKeyReader(type = type.key()), @template.planKeyWriter(type = type.key()), @template.planNode(type = type.value()))@elseif(type.isSet())JSONPlan.set(@template.planNode(type = type.element()))@elseif(type.isList())JSONPlan.list(@template.planNode(type = type.element()))@elseif(type.hasCompanion())JSONPlan.object("${type.name()}", ${type.nestedCompanion()}::new, ${type.nestedCompanion()}::toJSON)@else@template.planLeaf(type = type)@endif
```

(Single physical line — the expression embeds in a field initializer.)

- [ ] **Step 2: Create `planKeyReader.jte` and `planKeyWriter.jte`**

`planKeyReader.jte` — `Function<String, K>`, reusing `fromString.jte` exactly as the typed map observers did:

```jte
@param org.lattejava.json.jte.TypeView type
k -> @template.fromString(type = type, expr = "k")
```

`planKeyWriter.jte` — `Function<K, String>`, mirroring the key expressions of the old `mapObserver.jte` (`name()` for enums, identity for String, `toString()` otherwise):

```jte
@param org.lattejava.json.jte.TypeView type
!{
  String writer;
  if (type.isEnum()) {
    writer = type.simpleName() + "::name";
  } else if (type.isString()) {
    writer = "k -> k";
  } else {
    writer = "k -> k.toString()";
  }
}${writer}
```

(Both templates: single-expression bodies, no trailing newline — they embed mid-expression.)

- [ ] **Step 3: Create `planLeaf.jte` (scalar leaf expression)**

Reuses `fromString.jte`/`narrow.jte`/`arrayAppend.jte`/`memberCall.jte` inside the generated lambdas so every conversion is byte-identical to today's typed observers. The explicit type witness (`JSONPlan.<Integer>scalar`) makes inference unambiguous. The three leaf categories mirror the old observer branches (string-form / boolean / numeric):

```jte
@import org.lattejava.json.jte.TypeView
@param TypeView type
@if(type.isStringForm())JSONPlan.<@template.declType(type = type)>scalar("${type.name()}", value -> @template.fromString(type = type, expr = "value"), null, null, null, false, (b, e) -> b@template.arrayAppend(type = type, expr = "e"), (b, k, e) -> b.@template.memberCall(type = type, key = "k", val = "e"))@elseif(type.isBool())JSONPlan.<Boolean>scalar("${type.name()}", null, null, null, null, true, (b, e) -> b@template.arrayAppend(type = type, expr = "e"), (b, k, e) -> b.@template.memberCall(type = type, key = "k", val = "e"))@else JSONPlan.<@template.declType(type = type)>scalar("${type.name()}", null, value -> @template.narrow(type = type, source = "integer"), value -> @template.narrow(type = type, source = "bigInteger"), value -> @template.narrow(type = type, source = "decimal"), false, (b, e) -> b@template.arrayAppend(type = type, expr = "e"), (b, k, e) -> b.@template.memberCall(type = type, key = "k", val = "e"))@endif
```

Notes: `narrow.jte` emits expressions over a variable literally named `value`, which is exactly the lambda parameter name used here. `arrayAppend.jte` emits a leading-dot chain (`.integer(...)`), hence `b@template.arrayAppend(...)`; `memberCall.jte` emits `integer(k, e)` without the dot, hence `b.@template.memberCall(...)`. `JSONBuilder.integer(String, Number)` accepts the boxed values `memberCall` passes.

- [ ] **Step 4: Create `plan.jte` (per-member emission)**

Handles every collection member: the dynamic-map branch keeps its existing `any`-based serializer (moved verbatim from `mapObserver.jte`); all other collections emit a typed plan constant. The field's declared type is the concrete node record so observer constructors type-check:

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
@elseif(c.type().isMap())
  private static final JSONPlan.MapNode<@template.declType(type = c.type().key()), @template.declType(type = c.type().value())> ${c.name()}Plan = @template.planNode(type = c.type());
@elseif(c.type().isSet())
  private static final JSONPlan.SetNode<@template.declType(type = c.type().element())> ${c.name()}Plan = @template.planNode(type = c.type());
@else
  private static final JSONPlan.ListNode<@template.declType(type = c.type().element())> ${c.name()}Plan = @template.planNode(type = c.type());
@endif
```

- [ ] **Step 5: Rewire `companion.jte`**

Three changes:

(a) Imports — after the existing `Any*Observer` guard block, add the plan imports when any non-dynamic collection member exists. Add a helper to `CompanionView` (alphabetical position between `hasDynamicMap()` and `internalPackage()`):

```java
  /** Whether any member uses a typed collection plan (a non-dynamic-map collection member). */
  public boolean hasPlan() {
    return components.stream().anyMatch(c -> c.type().isCollection() && !c.isCatchAll() && !c.type().isDynamicMap());
  }
```

and in the template's import section:

```jte
@if(view.hasPlan())
import ${view.internalPackage()}.JSONPlan;
import ${view.internalPackage()}.JSONPlanArrayObserver;
import ${view.internalPackage()}.JSONPlanMapObserver;
@endif
```

(b) Serialize call site — in the `builder(...)` loop, the List/Set arm changes from

```jte
@elseif(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.read()} == null ? null : ${c.name()}ToJSON(value.${c.read()}))
```

to

```jte
@elseif(c.type().isList() || c.type().isSet())
        .array("${c.wireKey()}", value.${c.read()} == null ? null : JSONPlan.write(${c.name()}Plan, value.${c.read()}, ${view.omitNulls()}))
```

(The Map arm changes in Task 5; do not touch it yet.)

(c) Collection-emission loop — the current loop

```jte
@for(Component c : view.collectionComponents())
@if(c.type().isMap())
@template.mapObserver(c = c, omitNulls = view.omitNulls())
@else
@template.arrayObserver(c = c)
@endif
@endfor
```

becomes

```jte
@for(Component c : view.collectionComponents())
@if(c.type().isMap())
@template.mapObserver(c = c, omitNulls = view.omitNulls())
@else
@template.plan(c = c, omitNulls = view.omitNulls())
@endif
@endfor
```

(Maps still route to `mapObserver` until Task 5.)

(d) Delete `src/main/jte/arrayObserver.jte`.

- [ ] **Step 6: Rewire `observerBody.jte` `beginArray`**

The List/Set arm changes from

```jte
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())ArrayObserver(); }
```

to

```jte
      case "${c.wireKey()}" -> { return JSONPlanArrayObserver.of(${c.name()}Plan); }
```

(The `array(key, value)` assignment arm with its `declType` cast is unchanged.)

- [ ] **Step 7: Run the full suite for parity**

Run: `latte test`
Expected: PASS — every List/Set fixture (`Lists`, `Sets`, `EnumColls`, `User.prior`/`User.seen`, polymorphic lists, …) round-trips byte-identically through plans. If a test fails, diff the generated companion under `build/test/generated/<fixture>/` against expectations — the failure mode is almost always a leaf-lambda expression differing from the old observer's conversion.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/jte src/main/java/org/lattejava/json/jte/CompanionView.java
git commit -m "feat: Migrate List/Set member codegen onto JSONPlan type plans

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Migrate `Map` members onto plans; delete `mapObserver.jte`

**Files:**
- Modify: `src/main/jte/companion.jte` (Map serialize arm + collection loop)
- Modify: `src/main/jte/observerBody.jte:73-88` (`beginObject` Map routing)
- Delete: `src/main/jte/mapObserver.jte`, `src/main/jte/cap.jte`

- [ ] **Step 1: Switch the Map serialize arm in `companion.jte`**

From

```jte
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.read()} == null ? null : ${c.name()}ToJSON(value.${c.read()}))
```

to (dynamic maps keep their `<name>ToJSON`; typed maps use the plan writer):

```jte
@elseif(c.type().isDynamicMap())
        .object("${c.wireKey()}", value.${c.read()} == null ? null : ${c.name()}ToJSON(value.${c.read()}))
@elseif(c.type().isMap())
        .object("${c.wireKey()}", value.${c.read()} == null ? null : JSONPlan.write(${c.name()}Plan, value.${c.read()}, ${view.omitNulls()}))
```

(The `isDynamicMap` arm must precede the `isMap` arm — a dynamic map is also `isMap()`.)

- [ ] **Step 2: Collapse the collection-emission loop**

```jte
@for(Component c : view.collectionComponents())
@template.plan(c = c, omitNulls = view.omitNulls())
@endfor
```

`plan.jte` already handles the dynamic-map branch (Task 4 Step 4), so `mapObserver.jte` is now unreferenced. Delete it.

- [ ] **Step 3: Rewire `observerBody.jte` `beginObject` Map routing**

The typed-map arm changes from

```jte
@elseif(c.deserialize() && c.type().isMap())
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())MapObserver(); }
```

to

```jte
@elseif(c.deserialize() && c.type().isMap())
      case "${c.wireKey()}" -> { return new JSONPlanMapObserver<>(${c.name()}Plan); }
```

(The `isDynamicMap` arm above it and the `object(key, value)` assignment arm below are unchanged.) `cap.jte` now has no callers — delete it (verify with `grep -rn "template.cap" src/main/jte/` first; if anything still references it, leave it and note why).

- [ ] **Step 4: Run the full suite for parity**

Run: `latte test`
Expected: PASS — every Map fixture (`Maps`, `KeyedMaps`, `User.byType`, dynamic maps, catch-all coexistence) round-trips byte-identically.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/jte
git commit -m "feat: Migrate Map member codegen onto JSONPlan type plans

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Recursive validation + the motivating case (`Map<String, List<Domain>>`)

Until now, nested collections are still rejected at validation, so plans only ever ran one level deep. This task makes validation recursive and proves the full chain with the motivating fixture. `List<List<String>>` moves from rejected to legal.

**Files:**
- Modify: `src/main/java/org/lattejava/json/processor/AbstractValidator.java:162-196` (the `validateType` collection branches)
- Create: `src/test/resources/fixtures/deepcollections/module-info.java`, `src/test/resources/fixtures/deepcollections/demo/Product.java`, `src/test/resources/fixtures/deepcollections/demo/Catalog.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java`
- Modify: `src/test/resources/fixtures/badcollections/demo/RawColl.java` stays; **delete** `src/test/resources/fixtures/badcollections/demo/Nested.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/CollectionRejectionTest.java`

- [ ] **Step 1: Write the failing fixtures + codegen test**

`src/test/resources/fixtures/deepcollections/module-info.java`:

```java
module demo.deepcollections {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/deepcollections/demo/Product.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Product(String sku) {
}
```

`src/test/resources/fixtures/deepcollections/demo/Catalog.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Catalog(Map<String, List<Product>> byCategory, List<List<String>> grid) {
}
```

`src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DeepCollectionsCodegenTest {
  static ProcessorHarness.Result deep;

  @BeforeClass
  public void compileOnce() throws Exception {
    deep = ProcessorHarness.compile("deepcollections");
    assertTrue(deep.success(), deep.diagnostics().toString());
  }

  @Test
  public void mapOfListOfDomainRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Catalog");
      Class<?> j = loader.loadClass("demo.internal.CatalogJSON");
      String json = "{\"byCategory\":{\"tools\":[{\"sku\":\"a\"},{\"sku\":\"b\"}],\"toys\":[]},\"grid\":[]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var byCategory = (java.util.Map<?, ?>) t.getMethod("byCategory").invoke(o);
      var tools = (java.util.List<?>) byCategory.get("tools");
      assertEquals(tools.size(), 2);
      Class<?> product = loader.loadClass("demo.Product");
      assertEquals(product.getMethod("sku").invoke(tools.get(0)), "a");
      assertEquals(product.getMethod("sku").invoke(tools.get(1)), "b");
      assertTrue(((java.util.List<?>) byCategory.get("toys")).isEmpty());
      assertEquals(new java.util.ArrayList<>(byCategory.keySet()), java.util.List.of("tools", "toys"));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void listOfListRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Catalog");
      Class<?> j = loader.loadClass("demo.internal.CatalogJSON");
      String json = "{\"byCategory\":{},\"grid\":[[\"x\"],[\"y\",\"z\"]]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var grid = (java.util.List<?>) t.getMethod("grid").invoke(o);
      assertEquals(grid, java.util.List.of(java.util.List.of("x"), java.util.List.of("y", "z")));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=DeepCollectionsCodegenTest`
Expected: FAIL in `compileOnce` — the validator still rejects `Map<String, List<Product>>` with `"uses a nested collection as a Map value"` and `List<List<String>>` with `"uses a nested collection"`.

- [ ] **Step 3: Make validation recursive**

In `AbstractValidator.java`, replace the body of the `mt.isCollection()` branch of `validateType` so the entry point reads:

```java
  /** Validates that a member's type is serializable (collection/map/element constraints + scalar support). */
  protected boolean validateType(Element at, CharSequence name, TypeView mt) {
    if (mt.isCollection()) {
      // dynamic map: Map<String, Object> carries arbitrary JSON values, read/written via the Any* helpers.
      // Only legal as the member's direct type, so it is recognized here, before the recursive walk.
      if (mt.isDynamicMap()) {
        return true;
      }
      return validateCollectionTree(at, name, mt);
    }
    if (!isSupportedComponentType(mt)) {
      // ... existing scalar-branch code, UNCHANGED ...
```

and add the recursive walk as a new private method (alphabetically among the instance methods):

```java
  /**
   * Recursively validates a collection member's type tree: string-form keys at every Map level, no raw or
   * wildcard type arguments, and supported leaf types. {@code Map<String, Object>} (the dynamic-map shape)
   * is only legal as a member's direct type, never nested.
   */
  private boolean validateCollectionTree(Element at, CharSequence name, TypeView t) {
    if (t.isMap()) {
      TypeView k = t.key();
      TypeView v = t.value();
      if (k == null || !k.isStringForm()) {
        error(at, "@JSON member [" + name + "] has an unsupported Map key type ["
            + (k == null ? "?" : k.name()) + "] (Map key must be String, UUID, an enum, or a java.time type)");
        return false;
      }
      if (v == null) {
        error(at, "@JSON member [" + name + "] uses a raw or wildcard Map which is not supported");
        return false;
      }
      if (v.isCollection()) {
        return validateCollectionTree(at, name, v);
      }
      if (v.isObject()) {
        error(at, "@JSON member [" + name + "] has an unsupported Map value type [java.lang.Object] "
            + "(Map<String, Object> is only supported as a member's direct type)");
        return false;
      }
      if (!isSupportedComponentType(v)) {
        error(at, v.isRecord() && !v.isNested() ? notJSON(at, v)
            : "@JSON member [" + name + "] has an unsupported Map value type [" + v.name() + "]");
        return false;
      }
      return true;
    }
    TypeView e = t.element();
    if (e == null) {
      error(at, "@JSON member [" + name + "] uses a raw or wildcard " + t.kind() + " which is not supported");
      return false;
    }
    if (e.isCollection()) {
      return validateCollectionTree(at, name, e);
    }
    if (!isSupportedComponentType(e)) {
      error(at, e.isRecord() && !e.isNested() ? notJSON(at, e)
          : "@JSON member [" + name + "] has an unsupported " + t.kind() + " element type [" + e.name() + "]");
      return false;
    }
    return true;
  }
```

The old flat Map/List blocks inside `validateType` (the "nested collection as a Map value" and "uses a nested collection" rejections, the one-level key/value/element checks, and the dynamic-map acceptance inside the Map block) are all deleted — `validateCollectionTree` plus the early `isDynamicMap()` return replace them exactly.

- [ ] **Step 4: Restructure `badcollections` + `CollectionRejectionTest`**

Delete `src/test/resources/fixtures/badcollections/demo/Nested.java` (`List<List<String>>` is now legal and covered positively by `Catalog.grid`). `BadKey.java` and `RawColl.java` stay. Update `CollectionRejectionTest.java` to:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class CollectionRejectionTest {
  @Test
  public void nonStringFormMapKeyRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("m")),
        "expected Map-key error for [m], got: " + r.diagnostics());
  }

  @Test
  public void rawCollectionRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "raw collection member must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("raw or wildcard") && d.contains("[raw]")),
        "expected raw-collection error for [raw], got: " + r.diagnostics());
  }

  @Test
  public void wildcardCollectionRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "unbounded-wildcard collection member must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("element type") && d.contains("anySet")),
        "expected unsupported-element error for [anySet], got: " + r.diagnostics());
  }
}
```

Note: the raw-collection diagnostic does not bracket the member name today (`name` is interpolated bare); the new message text in Step 3 says `member [raw]`, which contains `[raw]`. The wildcard member `Set<?> anySet` produces the "unsupported Set element type [?]" path (a wildcard's `TypeView` is non-null, non-collection, unsupported).

- [ ] **Step 5: Run the tests**

Run: `latte test --test=DeepCollectionsCodegenTest` → PASS (2 tests).
Run: `latte test --test=CollectionRejectionTest` → PASS (3 tests).

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/org/lattejava/json/processor/AbstractValidator.java \
        src/test/resources/fixtures/deepcollections src/test/resources/fixtures/badcollections \
        src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java \
        src/test/java/org/lattejava/json/tests/processor/CollectionRejectionTest.java
git commit -m "feat: Recursive collection validation; Map<String, List<Domain>> and List<List<E>> supported

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Deeper shapes — Set values, map-in-map, three levels, polymorphic leaves

**Files:**
- Create: `src/test/resources/fixtures/deepcollections/demo/Warehouse.java`, `.../demo/Region.java`, `.../demo/Shape.java`, `.../demo/Circle.java`, `.../demo/Square.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java`

- [ ] **Step 1: Add the fixtures**

`src/test/resources/fixtures/deepcollections/demo/Region.java` (enum for string-form keys):

```java
package demo;

public enum Region {
  EAST,
  WEST
}
```

`src/test/resources/fixtures/deepcollections/demo/Shape.java` (polymorphic leaf):

```java
package demo;

import module org.lattejava.json;

@JSON
@JSONTypeInfo(property = "type")
public sealed interface Shape permits Circle, Square {
}
```

`src/test/resources/fixtures/deepcollections/demo/Circle.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Circle(int radius) implements Shape {
}
```

`src/test/resources/fixtures/deepcollections/demo/Square.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Square(int side) implements Shape {
}
```

`src/test/resources/fixtures/deepcollections/demo/Warehouse.java` (Set values keyed by enum; map-in-map with object leaves; three levels with a `java.time` key and numeric narrowing; polymorphic leaves in a nested list):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record Warehouse(Map<Region, Set<Product>> stock,
                        Map<String, Map<String, Product>> index,
                        Map<String, List<Map<Instant, Integer>>> series,
                        Map<String, List<Shape>> shapes) {
}
```

- [ ] **Step 2: Add the failing tests**

Append to `DeepCollectionsCodegenTest`:

```java
  @Test
  public void enumKeyedSetValuesRoundTrip() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Warehouse");
      Class<?> j = loader.loadClass("demo.internal.WarehouseJSON");
      String json = "{\"stock\":{\"EAST\":[{\"sku\":\"a\"},{\"sku\":\"b\"}]},\"index\":{},\"series\":{},\"shapes\":{}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var stock = (java.util.Map<?, ?>) t.getMethod("stock").invoke(o);
      Class<?> region = loader.loadClass("demo.Region");
      Object east = Enum.valueOf(region.asSubclass(Enum.class), "EAST");
      var set = (java.util.Set<?>) stock.get(east);
      assertTrue(set instanceof java.util.LinkedHashSet, "Set value -> LinkedHashSet");
      assertEquals(set.size(), 2);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void mapInMapWithObjectLeavesRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Warehouse");
      Class<?> j = loader.loadClass("demo.internal.WarehouseJSON");
      String json = "{\"stock\":{},\"index\":{\"a\":{\"x\":{\"sku\":\"s1\"}}},\"series\":{},\"shapes\":{}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var index = (java.util.Map<?, ?>) t.getMethod("index").invoke(o);
      var inner = (java.util.Map<?, ?>) index.get("a");
      Class<?> product = loader.loadClass("demo.Product");
      assertEquals(product.getMethod("sku").invoke(inner.get("x")), "s1");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void threeLevelsWithTimeKeysAndNarrowingRoundTrip() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Warehouse");
      Class<?> j = loader.loadClass("demo.internal.WarehouseJSON");
      String json = "{\"stock\":{},\"index\":{},"
          + "\"series\":{\"cpu\":[{\"2026-06-12T00:00:00Z\":42}]},\"shapes\":{}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var series = (java.util.Map<?, ?>) t.getMethod("series").invoke(o);
      var list = (java.util.List<?>) series.get("cpu");
      var inner = (java.util.Map<?, ?>) list.getFirst();
      Object v = inner.get(java.time.Instant.parse("2026-06-12T00:00:00Z"));
      assertEquals(v, 42);
      assertTrue(v instanceof Integer, "narrowed to Integer, not Long");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void polymorphicLeavesInNestedListRoundTrip() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Warehouse");
      Class<?> j = loader.loadClass("demo.internal.WarehouseJSON");
      String json = "{\"stock\":{},\"index\":{},\"series\":{},"
          + "\"shapes\":{\"g\":[{\"type\":\"Circle\",\"radius\":1},{\"type\":\"Square\",\"side\":2}]}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var shapes = (java.util.Map<?, ?>) t.getMethod("shapes").invoke(o);
      var g = (java.util.List<?>) shapes.get("g");
      assertEquals(g.get(0).getClass().getSimpleName(), "Circle");
      assertEquals(g.get(1).getClass().getSimpleName(), "Square");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the test**

Run: `latte test --test=DeepCollectionsCodegenTest`
Expected: PASS (6 tests) — no production change should be needed; the plan machinery is recursive by construction. If a test fails, inspect the generated `WarehouseJSON.java` under `build/test/generated/deepcollections/` — likely culprits are enum imports in plans (should be satisfied: `collectEnums` recurses) or key-writer expressions.

- [ ] **Step 4: Run the full suite, then commit**

Run: `latte test` → PASS.

```bash
git add src/test/resources/fixtures/deepcollections \
        src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java
git commit -m "test: Deep collection shapes — Set values, map-in-map, three levels, polymorphic leaves

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Nulls, `omitNulls`, empty containers, ordering, naming strategy

**Files:**
- Create: `src/test/resources/fixtures/deepcollections/demo/Keep.java`, `.../demo/Snake.java`
- Modify: `src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java`

- [ ] **Step 1: Add the fixtures**

`src/test/resources/fixtures/deepcollections/demo/Keep.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON(omitNulls = false)
public record Keep(Map<String, List<Integer>> data) {
}
```

`src/test/resources/fixtures/deepcollections/demo/Snake.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Snake(Map<String, List<Integer>> deepData) {
}
```

- [ ] **Step 2: Add the failing tests**

Append to `DeepCollectionsCodegenTest`:

```java
  @Test
  public void nullMapEntryDroppedUnderOmitNullsTrue() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Catalog");      // omitNulls defaults to true
      Class<?> j = loader.loadClass("demo.internal.CatalogJSON");
      // a null LIST VALUE inside the map: captured on read, dropped on serialize under omitNulls=true
      String json = "{\"byCategory\":{\"a\":null,\"b\":[]},\"grid\":[]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var byCategory = (java.util.Map<?, ?>) t.getMethod("byCategory").invoke(o);
      assertTrue(byCategory.containsKey("a") && byCategory.get("a") == null);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"byCategory\":{\"b\":[]},\"grid\":[]}");
    }
  }

  @Test
  public void nullEntriesAndElementsKeptUnderOmitNullsFalse() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Keep");
      Class<?> j = loader.loadClass("demo.internal.KeepJSON");
      // null map entry AND null array element both survive under omitNulls=false
      String json = "{\"data\":{\"a\":null,\"b\":[1,null]}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void nullArrayElementsAlwaysWrittenEvenWithOmitNullsTrue() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Catalog");      // omitNulls=true
      Class<?> j = loader.loadClass("demo.internal.CatalogJSON");
      String json = "{\"byCategory\":{},\"grid\":[[\"x\",null]]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void wholeMemberNullFollowsFieldConvention() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> tc = loader.loadClass("demo.Catalog");     // omitNulls=true: omitted
      Class<?> jc = loader.loadClass("demo.internal.CatalogJSON");
      Object oc = jc.getMethod("fromJSON", String.class).invoke(null, "{}");
      assertNull(tc.getMethod("byCategory").invoke(oc));
      assertEquals(jc.getMethod("toJSON", tc).invoke(null, oc), "{}");
      Class<?> tk = loader.loadClass("demo.Keep");        // omitNulls=false: written
      Class<?> jk = loader.loadClass("demo.internal.KeepJSON");
      Object ok = jk.getMethod("fromJSON", String.class).invoke(null, "{}");
      assertEquals(jk.getMethod("toJSON", tk).invoke(null, ok), "{\"data\":null}");
    }
  }

  @Test
  public void namingStrategyAppliesToWireKeyOnly() throws Exception {
    try (var loader = (URLClassLoader) deep.loader()) {
      Class<?> t = loader.loadClass("demo.Snake");
      Class<?> j = loader.loadClass("demo.internal.SnakeJSON");
      // the MEMBER key is snake_cased; the map's own keys are data and never renamed
      String json = "{\"deep_data\":{\"someKey\":[1]}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var data = (java.util.Map<?, ?>) t.getMethod("deepData").invoke(o);
      assertEquals(data.get("someKey"), java.util.List.of(1));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
```

- [ ] **Step 3: Run the test**

Run: `latte test --test=DeepCollectionsCodegenTest`
Expected: PASS (11 tests). These are characterization tests over behavior the plan machinery already implements (`JSONPlan.write` null/omitNulls semantics were unit-tested in Task 1); a failure here means the codegen call site isn't threading `omitNulls` or the wire key correctly — investigate, do not weaken assertions.

- [ ] **Step 4: Run the full suite, then commit**

Run: `latte test` → PASS.

```bash
git add src/test/resources/fixtures/deepcollections \
        src/test/java/org/lattejava/json/tests/processor/DeepCollectionsCodegenTest.java
git commit -m "test: Nested collection null/omitNulls/ordering/naming behavior

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Rejection coverage at depth

**Files:**
- Create: `src/test/resources/fixtures/baddeep_objectvalue/` (module-info + demo/BadObjectValue.java)
- Create: `src/test/resources/fixtures/baddeep_key/` (module-info + demo/BadDeepKey.java)
- Create: `src/test/resources/fixtures/baddeep_plain/` (module-info + demo/BadPlain.java + demo/Plain.java)
- Create: `src/test/java/org/lattejava/json/tests/processor/DeepCollectionsRejectionTest.java`

(Each rejection shape gets its own fixture set so one diagnostic cannot mask another — the same pattern as `badcatchall_*`.)

- [ ] **Step 1: Add the rejection fixtures**

`src/test/resources/fixtures/baddeep_objectvalue/module-info.java`:

```java
module demo.baddeep_objectvalue {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/baddeep_objectvalue/demo/BadObjectValue.java` (nested dynamic-map shape + `List<Object>` — both `Object` leaves):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadObjectValue(Map<String, Map<String, Object>> nestedDynamic, Map<String, List<Object>> anyList) {
}
```

`src/test/resources/fixtures/baddeep_key/module-info.java`:

```java
module demo.baddeep_key {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/baddeep_key/demo/BadDeepKey.java` (non-string-form key at depth):

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadDeepKey(Map<String, Map<Integer, String>> byNumber) {
}
```

`src/test/resources/fixtures/baddeep_plain/module-info.java`:

```java
module demo.baddeep_plain {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/baddeep_plain/demo/Plain.java` (NOT `@JSON`-annotated):

```java
package demo;

public record Plain(String x) {
}
```

`src/test/resources/fixtures/baddeep_plain/demo/BadPlain.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record BadPlain(Map<String, List<Plain>> deep) {
}
```

- [ ] **Step 2: Write the rejection test**

`src/test/java/org/lattejava/json/tests/processor/DeepCollectionsRejectionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DeepCollectionsRejectionTest {
  @Test
  public void nestedObjectValueTypesRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_objectvalue");
    assertFalse(r.success(), "Object leaves inside collections must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("[java.lang.Object]") && d.contains("nestedDynamic")),
        "expected nested-dynamic-map rejection for [nestedDynamic], got: " + r.diagnostics());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("[java.lang.Object]") && d.contains("anyList")),
        "expected List<Object> rejection for [anyList], got: " + r.diagnostics());
  }

  @Test
  public void nonStringFormKeyAtDepthRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_key");
    assertFalse(r.success(), "Map<Integer, ...> at depth must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("[java.lang.Integer]") && d.contains("byNumber")),
        "expected deep Map-key error for [byNumber], got: " + r.diagnostics());
  }

  @Test
  public void unannotatedRecordAtDepthRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_plain");
    assertFalse(r.success(), "un-annotated record leaf at depth must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d -> d.contains("Plain")),
        "expected not-@JSON-annotated error mentioning Plain, got: " + r.diagnostics());
  }
}
```

- [ ] **Step 3: Run the test**

Run: `latte test --test=DeepCollectionsRejectionTest`
Expected: PASS (3 tests) — the recursive validator from Task 6 already produces all three rejections; these tests pin them. If `nestedObjectValueTypesRejected` fails on the `anyList` assertion, check that `validateCollectionTree`'s List/Set branch routes an `Object` element through `isSupportedComponentType` (which rejects it with `"unsupported List element type [java.lang.Object]"`).

- [ ] **Step 4: Run the full suite, then commit**

Run: `latte test` → PASS.

```bash
git add src/test/resources/fixtures/baddeep_objectvalue src/test/resources/fixtures/baddeep_key \
        src/test/resources/fixtures/baddeep_plain \
        src/test/java/org/lattejava/json/tests/processor/DeepCollectionsRejectionTest.java
git commit -m "test: Rejection coverage for Object leaves, deep keys, and un-annotated leaves

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Final verification + mark design implemented

**Files:**
- Modify: `docs/design/2026-06-12-nested-collections-design.md` (status line)

- [ ] **Step 1: Full suite + template-orphan check**

Run: `latte clean && latte test`
Expected: PASS, from a clean build (proves the JTE precompile step handles the new/deleted templates). Then verify no orphan references:

Run: `grep -rn "arrayObserver\|mapObserver\|template.cap" src/main/jte/ src/main/java/`
Expected: no matches (all three deleted templates unreferenced).

- [ ] **Step 2: Update the design doc status**

In `docs/design/2026-06-12-nested-collections-design.md`, change

```
**Status:** Draft (design); pending user review
```

to

```
**Status:** Implemented
```

- [ ] **Step 3: Commit**

```bash
git add docs/design/2026-06-12-nested-collections-design.md
git commit -m "docs: Mark nested-collections design as implemented

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** runtime plan model + walker (§2 → Task 1), plan observers (§2 → Task 2), recursive declarations (§3 prerequisite → Task 3), template replacement + companion/observerBody rewiring + unified migration (§3 → Tasks 4-5), recursive validation incl. lifted/preserved rejections (§4 → Task 6), motivating case + all promised positive shapes incl. polymorphic leaves and `List<List<E>>` (§Testing → Tasks 6-7), omitNulls/null/order/naming (§5, §Testing → Task 8 + Task 1 unit tests), Object-leaf/deep-key/notJSON rejections (§Non-goals, §Testing → Task 9), dynamic-map/catch-all untouched (§Non-goals → preserved branches in Tasks 4-5, parity-checked by the existing suite), `HelperEmitter`+glob build fact (§6 → Task 1/2). Every design section maps to a task.
- **Parity strategy:** Tasks 3, 4, 5 are pure migrations gated by the full existing suite (byte-exact round-trip assertions throughout); nested behavior only unlocks in Task 6 once the machinery is proven on one-level shapes.
- **Type consistency:** `JSONPlan.Node<T>`/`ListNode`/`SetNode`/`MapNode`/`ObjectLeaf`/`ScalarLeaf`/`KeyedWrite`, factories `list/set/map/object/scalar`, `JSONPlan.write(node, value, omitNulls)`, `JSONPlan.typeName(node)`, `JSONPlanArrayObserver.of(...)`, `new JSONPlanMapObserver<>(plan)`, `<name>Plan` field naming, and `CompanionView.hasPlan()` are used identically across all tasks.
- **Known judgment calls for the implementer:** (a) exact whitespace of single-line templates matters — match the existing expression-template style (`fromString.jte` et al.) with no trailing newline; (b) if generic wildcard switch patterns warn, keep wildcards + the `@SuppressWarnings` helper methods — do not introduce typed patterns; (c) Task 1 Step 4 adds only `"JSONPlan"` to HELPERS, Task 2 completes the list — keeps each task independently green.
- **Error-message contract:** new messages introduced: "raw or wildcard" (raw/wildcard containers), "(Map<String, Object> is only supported as a member's direct type)" (nested dynamic shape), runtime "unexpected JSON <kind> for element type [T]" / "for Map value type [T]". All bracket runtime values per `.claude/rules/error-messages.md`; updated rejection tests assert the new texts.
