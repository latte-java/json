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
