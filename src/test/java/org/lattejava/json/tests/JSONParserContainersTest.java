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
