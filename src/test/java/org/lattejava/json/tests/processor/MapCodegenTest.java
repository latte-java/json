/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class MapCodegenTest {
  static ProcessorHarness.Result coll;

  @BeforeClass
  public void compileOnce() throws Exception {
    coll = ProcessorHarness.compile("collections");
    assertTrue(coll.success(), coll.diagnostics().toString());
  }

  @Test
  public void roundTripsStringKeyMap() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> maps = loader.loadClass("demo.Maps");
      Class<?> mapsJson = loader.loadClass("demo.internal.MapsJSON");
      String json = "{\"counts\":{\"a\":1,\"b\":2},"
          + "\"labels\":{\"00000000-0000-0000-0000-000000000001\":\"one\"}}";
      Object o = mapsJson.getMethod("fromJSON", String.class).invoke(null, json);
      var counts = (java.util.Map<?, ?>) maps.getMethod("counts").invoke(o);
      assertEquals(counts.get("a"), 1);
      assertEquals(counts.get("b"), 2);
      var labels = (java.util.Map<?, ?>) maps.getMethod("labels").invoke(o);
      assertEquals(labels.get(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")), "one");
      assertEquals(mapsJson.getMethod("toJSON", maps).invoke(null, o), json);
    }
  }

  @Test
  public void emptyMapRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> maps = loader.loadClass("demo.Maps");
      Class<?> mapsJson = loader.loadClass("demo.internal.MapsJSON");
      Object o = mapsJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"counts\":{},\"labels\":{}}");
      assertTrue(((java.util.Map<?, ?>) maps.getMethod("counts").invoke(o)).isEmpty());
      assertEquals(mapsJson.getMethod("toJSON", maps).invoke(null, o), "{\"counts\":{},\"labels\":{}}");
    }
  }

  @Test
  public void mapPreservesInsertionOrder() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> maps = loader.loadClass("demo.Maps");
      Class<?> mapsJson = loader.loadClass("demo.internal.MapsJSON");
      Object o = mapsJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"counts\":{\"z\":1,\"y\":2,\"x\":3},\"labels\":{}}");
      var counts = (java.util.Map<?, ?>) maps.getMethod("counts").invoke(o);
      assertEquals(new java.util.ArrayList<>(counts.keySet()), java.util.List.of("z", "y", "x"));
    }
  }
}
