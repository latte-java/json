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

  @Test
  public void roundTripsEnumKeyMap() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> keyedMaps = loader.loadClass("demo.KeyedMaps");
      Class<?> keyedMapsJson = loader.loadClass("demo.internal.KeyedMapsJSON");
      Class<?> color = loader.loadClass("demo.Color");
      String json = "{\"byColor\":{\"RED\":1,\"BLUE\":2},\"byTime\":{}}";
      Object o = keyedMapsJson.getMethod("fromJSON", String.class).invoke(null, json);
      var byColor = (java.util.Map<?, ?>) keyedMaps.getMethod("byColor").invoke(o);
      Object red = Enum.valueOf(color.asSubclass(Enum.class), "RED");
      Object blue = Enum.valueOf(color.asSubclass(Enum.class), "BLUE");
      assertEquals(byColor.get(red), 1);
      assertEquals(byColor.get(blue), 2);
      assertEquals(new java.util.ArrayList<>(byColor.keySet()), java.util.List.of(red, blue));
      assertEquals(keyedMapsJson.getMethod("toJSON", keyedMaps).invoke(null, o), json);
    }
  }

  @Test
  public void roundTripsJavaTimeKeyMap() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> keyedMaps = loader.loadClass("demo.KeyedMaps");
      Class<?> keyedMapsJson = loader.loadClass("demo.internal.KeyedMapsJSON");
      String json = "{\"byColor\":{},\"byTime\":{\"2026-05-16T00:00:00Z\":\"launch\"}}";
      Object o = keyedMapsJson.getMethod("fromJSON", String.class).invoke(null, json);
      var byTime = (java.util.Map<?, ?>) keyedMaps.getMethod("byTime").invoke(o);
      assertEquals(byTime.get(java.time.Instant.parse("2026-05-16T00:00:00Z")), "launch");
      assertEquals(keyedMapsJson.getMethod("toJSON", keyedMaps).invoke(null, o), json);
    }
  }
}
