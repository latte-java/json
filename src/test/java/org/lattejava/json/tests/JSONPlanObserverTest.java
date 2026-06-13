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
      assertTrue(expected.getMessage().contains("[List<java.lang.Integer>]"), expected.getMessage());
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

  @Test
  public void readsNestedArraysThroughArrayObserver() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(JSONPlan.list(intLeaf())));
    Map<String, List<List<Integer>>> v =
        new JSONParser().parse("{\"a\":[[1,2],[3]]}", new JSONPlanMapObserver<>(plan));
    assertEquals(v.get("a"), List.of(List.of(1, 2), List.of(3)));
  }

  @Test
  public void wrongScalarKindInArrayThrows() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(intLeaf()));
    try {
      new JSONParser().parse("{\"a\":[\"x\"]}", new JSONPlanMapObserver<>(plan));
      fail("expected JSONProcessingException");
    } catch (JSONProcessingException expected) {
      assertTrue(expected.getMessage().contains("element type [java.lang.Integer]"), expected.getMessage());
    }
  }

  @Test
  public void scalarWhereNestedArrayExpectedThrows() {
    var plan = JSONPlan.map(k -> k, k -> k, JSONPlan.list(JSONPlan.list(intLeaf())));
    try {
      new JSONParser().parse("{\"a\":[1]}", new JSONPlanMapObserver<>(plan));
      fail("expected JSONProcessingException");
    } catch (JSONProcessingException expected) {
      assertTrue(expected.getMessage().contains("element type [List<java.lang.Integer>]"), expected.getMessage());
    }
  }
}
