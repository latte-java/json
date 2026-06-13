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
}
