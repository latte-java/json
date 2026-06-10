/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ClassCodegenTest {
  static ProcessorHarness.Result classes;

  @BeforeClass
  public void compileOnce() throws Exception {
    classes = ProcessorHarness.compile("classes");
    assertTrue(classes.success(), classes.diagnostics().toString());
  }

  @Test
  public void pointRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Point");
      Class<?> j = loader.loadClass("demo.internal.PointJSON");
      String json = "{\"x\":1,\"y\":2}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getX").invoke(o), 1);
      assertEquals(t.getMethod("getY").invoke(o), 2);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void mixedAccessorsResolve() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Mixed");
      Class<?> j = loader.loadClass("demo.internal.MixedJSON");
      String json = "{\"name\":\"a\",\"active\":true,\"count\":3,\"tag\":\"t\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      // serialize reads via getName()/isActive()/count()/public field tag
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void namingAndWriteOnlyOnParameters() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Configured");
      Class<?> j = loader.loadClass("demo.internal.ConfiguredJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{\"user_name\":\"a\",\"secret\":\"s\"}");
      assertEquals(t.getMethod("getUserName").invoke(o), "a");
      // snake_cased key; secret is write-only (no reader) so it's omitted on serialize
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"user_name\":\"a\"}");
    }
  }

  @Test
  public void catchAllOnParameter() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Caught");
      Class<?> j = loader.loadClass("demo.internal.CaughtJSON");
      String json = "{\"id\":\"a\",\"x\":42,\"y\":true}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      var extras = (java.util.Map<?, ?>) t.getMethod("getExtras").invoke(o);
      assertEquals(extras.get("x"), 42L);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void classNestedInRecord() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.Household");
      Class<?> j = loader.loadClass("demo.internal.HouseholdJSON");
      String json = "{\"name\":\"h\",\"origin\":{\"x\":1,\"y\":2}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void fieldPoliciesAndCatchAllOnParameters() throws Exception {
    try (var loader = (URLClassLoader) classes.loader()) {
      Class<?> t = loader.loadClass("demo.ParamAnnotated");
      Class<?> j = loader.loadClass("demo.internal.ParamAnnotatedJSON");
      // @JSONField(name) rename, @JSONField(format), @JSONField(instant) epoch, and @JSONCatchAll all on ctor params
      String json = "{\"user_id\":\"u1\",\"born\":\"03/14/2020\",\"seen\":1700000000,\"role\":\"admin\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getUserId").invoke(o), "u1");
      assertEquals(t.getMethod("getBorn").invoke(o), java.time.LocalDate.of(2020, 3, 14));
      assertEquals(t.getMethod("getSeen").invoke(o), java.time.Instant.ofEpochSecond(1700000000L));
      var extras = (java.util.Map<?, ?>) t.getMethod("getExtras").invoke(o);
      assertEquals(extras.get("role"), "admin");           // unknown key captured by the @JSONCatchAll param
      assertFalse(extras.containsKey("user_id"));           // named params are not captured
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }
}
