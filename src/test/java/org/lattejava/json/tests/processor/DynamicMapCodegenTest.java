/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DynamicMapCodegenTest {
  static ProcessorHarness.Result dyn;

  @BeforeClass
  public void compileOnce() throws Exception {
    dyn = ProcessorHarness.compile("dynamicmap");
    assertTrue(dyn.success(), dyn.diagnostics().toString());
  }

  @Test
  public void capturesNaturalShapesAndRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      String json = "{\"id\":\"a\",\"prefs\":{\"s\":\"x\",\"n\":42,\"b\":true,"
          + "\"obj\":{\"k\":\"v\"},\"arr\":[1,2]}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("id").invoke(o), "a");
      var prefs = (java.util.Map<?, ?>) t.getMethod("prefs").invoke(o);
      assertEquals(prefs.get("s"), "x");
      assertEquals(prefs.get("n"), 42L);
      assertEquals(prefs.get("b"), Boolean.TRUE);
      assertTrue(prefs.get("obj") instanceof java.util.LinkedHashMap, "nested object -> LinkedHashMap");
      assertEquals(((java.util.Map<?, ?>) prefs.get("obj")).get("k"), "v");
      assertTrue(prefs.get("arr") instanceof java.util.ArrayList, "nested array -> ArrayList");
      assertEquals(((java.util.List<?>) prefs.get("arr")), java.util.List.of(1L, 2L));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void preservesInsertionOrder() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class)
          .invoke(null, "{\"id\":\"a\",\"prefs\":{\"z\":1,\"y\":2,\"x\":3}}");
      var prefs = (java.util.Map<?, ?>) t.getMethod("prefs").invoke(o);
      assertEquals(new java.util.ArrayList<>(prefs.keySet()), java.util.List.of("z", "y", "x"));
    }
  }

  @Test
  public void emptyDynamicMapRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"prefs\":{}}");
      assertTrue(((java.util.Map<?, ?>) t.getMethod("prefs").invoke(o)).isEmpty());
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"prefs\":{}}");
    }
  }

  @Test
  public void omitNullsTrueDropsNullEntryOnSerialize() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");      // omitNulls defaults to true
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class)
          .invoke(null, "{\"id\":\"a\",\"prefs\":{\"k\":null,\"j\":1}}");
      var prefs = (java.util.Map<?, ?>) t.getMethod("prefs").invoke(o);
      assertTrue(prefs.containsKey("k") && prefs.get("k") == null, "null entry captured on read");
      // serialize drops the null entry under omitNulls=true
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"prefs\":{\"j\":1}}");
    }
  }

  @Test
  public void omitNullsFalseKeepsNullEntryAndNesting() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Keep");
      Class<?> j = loader.loadClass("demo.internal.KeepJSON");
      // null entry at top level and inside a nested object both survive under omitNulls=false
      String json = "{\"data\":{\"k\":null,\"obj\":{\"q\":null}}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void wholeMapNullOmittedUnderOmitNullsTrue() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");      // omitNulls=true
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\"}");
      assertNull(t.getMethod("prefs").invoke(o), "absent map field is null");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\"}");
    }
  }

  @Test
  public void wholeMapNullWrittenUnderOmitNullsFalse() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Keep");          // omitNulls=false
      Class<?> j = loader.loadClass("demo.internal.KeepJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{}");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"data\":null}");
    }
  }

  @Test
  public void usesNamingStrategyWireKey() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Snake");
      Class<?> j = loader.loadClass("demo.internal.SnakeJSON");
      String json = "{\"user_prefs\":{\"a\":1}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var prefs = (java.util.Map<?, ?>) t.getMethod("userPrefs").invoke(o);
      assertEquals(prefs.get("a"), 1L);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void coexistsWithCatchAll() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Mixed");
      Class<?> j = loader.loadClass("demo.internal.MixedJSON");
      // "meta" is the known dynamic-map key (nested); "x" and "y" are unknown -> catch-all (spread)
      String json = "{\"meta\":{\"a\":1},\"x\":7,\"y\":\"z\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var meta = (java.util.Map<?, ?>) t.getMethod("meta").invoke(o);
      assertEquals(meta.get("a"), 1L);
      var extras = (java.util.Map<?, ?>) t.getMethod("extras").invoke(o);
      assertEquals(extras.get("x"), 7L);
      assertEquals(extras.get("y"), "z");
      assertFalse(extras.containsKey("meta"), "known dynamic-map key must not leak into the catch-all");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void scalarAtDynamicMapKeyIsIgnoredLeniently() throws Exception {
    try (var loader = (URLClassLoader) dyn.loader()) {
      Class<?> t = loader.loadClass("demo.Settings");
      Class<?> j = loader.loadClass("demo.internal.SettingsJSON");
      // a scalar (non-object) at the dynamic-map key has no matching object handler;
      // with no catch-all and lenient (non-strict) defaults it is ignored and the field stays null
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"prefs\":5}");
      assertEquals(t.getMethod("id").invoke(o), "a");
      assertNull(t.getMethod("prefs").invoke(o), "scalar at dynamic-map key is ignored leniently");
    }
  }
}
