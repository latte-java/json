/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class BigNumberCodegenTest {
  static ProcessorHarness.Result extras;

  @BeforeClass
  public void compileOnce() throws Exception {
    extras = ProcessorHarness.compile("extras");
    assertTrue(extras.success(),
        "extras fixture must compile (all new types supported); diagnostics: "
        + extras.diagnostics());
  }

  @Test
  public void companionsGeneratedForAllExtras() {
    for (String n : new String[]{"BagJSON", "IdsJSON", "TimesJSON"}) {
      assertTrue(java.nio.file.Files.exists(
          extras.outputDir().resolve("demo/internal/" + n + ".class")),
          "expected generated companion [" + n + "]");
    }
  }

  @Test
  public void roundTripsBag() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> bag  = loader.loadClass("demo.Bag");
      Class<?> bagJson = loader.loadClass("demo.internal.BagJSON");
      String json = "{\"big\":99999999999999999999,\"money\":12.34}";
      Object obj = bagJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(bag.getMethod("big").invoke(obj), new java.math.BigInteger("99999999999999999999"));
      assertEquals(bag.getMethod("money").invoke(obj), new java.math.BigDecimal("12.34"));
      assertEquals(bagJson.getMethod("toJSON", bag).invoke(null, obj), json);
    }
  }

  @Test
  public void bigDecimalAcceptsIntegerJson() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> bag  = loader.loadClass("demo.Bag");
      Class<?> bagJson = loader.loadClass("demo.internal.BagJSON");
      Object obj = bagJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"big\":5,\"money\":7}");
      assertEquals(bag.getMethod("big").invoke(obj), new java.math.BigInteger("5"));
      assertEquals(bag.getMethod("money").invoke(obj), new java.math.BigDecimal("7"));
    }
  }

  @Test
  public void bigIntegerRejectsFractionalJson() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> bagJson = loader.loadClass("demo.internal.BagJSON");
      try {
        bagJson.getMethod("fromJSON", String.class)
            .invoke(null, "{\"big\":1.5,\"money\":2}");
        fail("fractional JSON into BigInteger must throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException",
            "expected JSONProcessingException, got: " + e.getCause());
      }
    }
  }
}
