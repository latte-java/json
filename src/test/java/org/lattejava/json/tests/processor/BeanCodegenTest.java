/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class BeanCodegenTest {
  static ProcessorHarness.Result beans;

  @BeforeClass
  public void compileOnce() throws Exception {
    beans = ProcessorHarness.compile("beans");
    assertTrue(beans.success(), beans.diagnostics().toString());
  }

  @Test
  public void beanRoundTripsViaSetters() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Account");
      Class<?> j = loader.loadClass("demo.internal.AccountJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"id\":\"a\",\"balance\":5,\"feeBps\":25}");
      assertEquals(t.getMethod("getId").invoke(o), "a");
      assertEquals(t.getMethod("getBalance").invoke(o), 5);
      // feeBps is read-only (computed, no setter): serialized, not written back
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"balance\":5,\"feeBps\":25}");
    }
  }

  @Test
  public void publicFieldsRoundTrip() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.PublicFields");
      Class<?> j = loader.loadClass("demo.internal.PublicFieldsJSON");
      String json = "{\"name\":\"x\",\"active\":true}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void inheritedPropertiesBaseFirst() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Employee");
      Class<?> j = loader.loadClass("demo.internal.EmployeeJSON");
      // base "name" first, then "id"
      String json = "{\"name\":\"a\",\"id\":7}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getName").invoke(o), "a");
      assertEquals(t.getMethod("getId").invoke(o), 7);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void transientAndStaticSkipped_configOnAccessor_catchAll() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Tagged");
      Class<?> j = loader.loadClass("demo.internal.TaggedJSON");
      // KIND (static) and cacheHits (transient) absent; label -> "tag" (getter @JSONField); x/y captured
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"tag\":\"L\",\"x\":1,\"y\":true}");
      assertEquals(t.getMethod("getLabel").invoke(o), "L");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"tag\":\"L\",\"x\":1,\"y\":true}");
    }
  }

  @Test
  public void beanNestedInRecord() throws Exception {
    try (var loader = (URLClassLoader) beans.loader()) {
      Class<?> t = loader.loadClass("demo.Box");
      Class<?> j = loader.loadClass("demo.internal.BoxJSON");
      String json = "{\"label\":\"b\",\"account\":{\"id\":\"a\",\"balance\":5,\"feeBps\":25}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
}
