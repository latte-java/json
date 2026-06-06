/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolyUsageTest {
  static ProcessorHarness.Result poly;

  @BeforeClass
  public void compileOnce() throws Exception {
    poly = ProcessorHarness.compile("poly");
    assertTrue(poly.success(), poly.diagnostics().toString());
  }

  @Test
  public void polymorphicFieldRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> ownerJson = loader.loadClass("demo.internal.OwnerJSON");
      Class<?> owner = loader.loadClass("demo.Owner");
      String json = "{\"name\":\"Sam\",\"pet\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}}";
      Object o = ownerJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(ownerJson.getMethod("toJSON", owner).invoke(null, o), json);
    }
  }

  @Test
  public void polymorphicListRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> kennelJson = loader.loadClass("demo.internal.KennelJSON");
      Class<?> kennel = loader.loadClass("demo.Kennel");
      String json = "{\"name\":\"Acme\",\"pets\":["
          + "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3},"
          + "{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}]}";
      Object o = kennelJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(kennelJson.getMethod("toJSON", kennel).invoke(null, o), json);
    }
  }

  @Test
  public void polymorphicMapValueRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> registryJson = loader.loadClass("demo.internal.RegistryJSON");
      Class<?> registry = loader.loadClass("demo.Registry");
      String json = "{\"byId\":{"
          + "\"a\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3},"
          + "\"b\":{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}}}";
      Object o = registryJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(registryJson.getMethod("toJSON", registry).invoke(null, o), json);
    }
  }

  @Test
  public void nestedPolymorphismRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> householdJson = loader.loadClass("demo.internal.HouseholdJSON");
      Class<?> household = loader.loadClass("demo.Household");
      String json = "{\"owner\":{\"name\":\"Sam\","
          + "\"pet\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}}}";
      Object o = householdJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(householdJson.getMethod("toJSON", household).invoke(null, o), json);
    }
  }
}
