/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class SetCodegenTest {
  static ProcessorHarness.Result coll;

  @BeforeClass
  public void compileOnce() throws Exception {
    coll = ProcessorHarness.compile("collections");
    assertTrue(coll.success(), coll.diagnostics().toString());
  }

  @Test
  public void roundTripsSetsPreservingInsertionOrder() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> sets = loader.loadClass("demo.Sets");
      Class<?> setsJson = loader.loadClass("demo.internal.SetsJSON");
      String json = "{\"names\":[\"c\",\"a\",\"b\"],\"codes\":[3,1,2]}";
      Object o = setsJson.getMethod("fromJSON", String.class).invoke(null, json);
      Object names = sets.getMethod("names").invoke(o);
      assertTrue(names instanceof java.util.LinkedHashSet, "Set must be LinkedHashSet");
      assertEquals(new java.util.ArrayList<>((java.util.Set<?>) names),
          java.util.List.of("c", "a", "b"), "insertion order preserved");
      assertEquals(setsJson.getMethod("toJSON", sets).invoke(null, o), json);
    }
  }

  @Test
  public void duplicateElementsCollapse() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> sets = loader.loadClass("demo.Sets");
      Class<?> setsJson = loader.loadClass("demo.internal.SetsJSON");
      Object o = setsJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"names\":[\"a\",\"a\",\"b\"],\"codes\":[1,1]}");
      assertEquals(((java.util.Set<?>) sets.getMethod("names").invoke(o)).size(), 2);
    }
  }
}
