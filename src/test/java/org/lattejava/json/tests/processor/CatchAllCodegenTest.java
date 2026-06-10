/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class CatchAllCodegenTest {
  static ProcessorHarness.Result catchall;

  @BeforeClass
  public void compileOnce() throws Exception {
    catchall = ProcessorHarness.compile("catchall");
    assertTrue(catchall.success(), catchall.diagnostics().toString());
  }

  @Test
  public void capturesUnknownsAtNaturalShapes() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Response");
      Class<?> j = loader.loadClass("demo.internal.ResponseJSON");
      String json = "{\"id\":\"a\",\"code\":7,\"s\":\"x\",\"n\":42,\"b\":true,\"z\":null,"
          + "\"obj\":{\"k\":\"v\"},\"arr\":[1,2]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("id").invoke(o), "a");
      assertEquals(t.getMethod("code").invoke(o), 7);
      var extras = (java.util.Map<?, ?>) t.getMethod("extras").invoke(o);
      assertEquals(extras.get("s"), "x");
      assertEquals(extras.get("n"), 42L);
      assertEquals(extras.get("b"), Boolean.TRUE);
      assertTrue(extras.containsKey("z") && extras.get("z") == null, "null entry must be captured");
      assertTrue(extras.get("obj") instanceof java.util.LinkedHashMap, "nested object -> LinkedHashMap");
      assertEquals(((java.util.Map<?, ?>) extras.get("obj")).get("k"), "v");
      assertTrue(extras.get("arr") instanceof java.util.ArrayList, "nested array -> ArrayList");
      assertEquals(((java.util.List<?>) extras.get("arr")), java.util.List.of(1L, 2L));
      // id/code are NOT captured into the catch-all
      assertFalse(extras.containsKey("id"));
      assertFalse(extras.containsKey("code"));
    }
  }

  @Test
  public void spreadsAndRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Response");
      Class<?> j = loader.loadClass("demo.internal.ResponseJSON");
      String json = "{\"id\":\"a\",\"code\":7,\"s\":\"x\",\"n\":42,\"b\":true,"
          + "\"obj\":{\"k\":\"v\"},\"arr\":[1,2]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      // named fields first, then the catch-all entries spread at top level in insertion order
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void emptyCatchAllAddsNoKeys() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Response");
      Class<?> j = loader.loadClass("demo.internal.ResponseJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"code\":7}");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"code\":7}");
    }
  }

  @Test
  public void omitNullsFalseKeepsNullInArrayNestedObject() throws Exception {
    try (var loader = (URLClassLoader) catchall.loader()) {
      Class<?> t = loader.loadClass("demo.Loose");
      Class<?> j = loader.loadClass("demo.internal.LooseJSON");
      // a null member of an object nested inside a catch-all array must survive under omitNulls=false
      String json = "{\"arr\":[{\"k\":null}],\"z\":null}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
}
