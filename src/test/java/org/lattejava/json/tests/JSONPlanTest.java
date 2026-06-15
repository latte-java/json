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
        (w, e) -> w.integerElement(e == null ? null : e.longValue()),
        (w, k, e) -> w.integer(k, e));
  }

  static JSONPlan.Node<String> stringLeaf() {
    return JSONPlan.scalar("java.lang.String",
        value -> value, null, null, null, false,
        (w, e) -> w.stringElement(e),
        (w, k, e) -> w.string(k, e));
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
    JSONPlan.Node<String> fake = JSONPlan.object("demo.Fake", AnyObjectObserver::new,
        (w, s) -> { w.beginObject(); w.string("v", s); w.endObject(); });
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

  @Test
  public void typeNameDescribesNodes() {
    assertEquals(JSONPlan.typeName(JSONPlan.list(stringLeaf())), "List<java.lang.String>");
    assertEquals(JSONPlan.typeName(JSONPlan.set(stringLeaf())), "Set<java.lang.String>");
    assertEquals(JSONPlan.typeName(JSONPlan.map(k -> k, k -> k, JSONPlan.list(stringLeaf()))), "Map<?, List<java.lang.String>>");
  }

  @Test
  public void writesSetValues() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.set(stringLeaf()));
    Map<String, Set<String>> v = new LinkedHashMap<>();
    v.put("s", new LinkedHashSet<>(List.of("b", "a")));
    assertEquals(JSONPlan.write(plan, v, true), "{\"s\":[\"b\",\"a\"]}");
  }
}
