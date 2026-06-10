/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class EnumCodegenTest {
  static ProcessorHarness.Result extras;

  @BeforeClass
  public void compileOnce() throws Exception {
    extras = ProcessorHarness.compile("extras");
    assertTrue(extras.success(), extras.diagnostics().toString());
  }

  @Test
  public void roundTripsEnumViaName() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> ids  = loader.loadClass("demo.Ids");
      Class<?> idsJson = loader.loadClass("demo.internal.IdsJSON");
      String json = "{\"id\":\"00000000-0000-0000-0000-000000000009\",\"color\":\"GREEN\"}";
      Object obj = idsJson.getMethod("fromJSON", String.class).invoke(null, json);
      Object color = ids.getMethod("color").invoke(obj);
      assertEquals(color.toString(), "GREEN");
      assertEquals(idsJson.getMethod("toJSON", ids).invoke(null, obj), json);
    }
  }

  @Test
  public void unknownEnumConstantThrowsJSONProcessingException() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> idsJson = loader.loadClass("demo.internal.IdsJSON");
      try {
        idsJson.getMethod("fromJSON", String.class)
            .invoke(null, "{\"id\":\"00000000-0000-0000-0000-000000000009\",\"color\":\"PURPLE\"}");
        fail("unknown enum constant must throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException",
            "expected JSONProcessingException, got: " + e.getCause());
      }
    }
  }

  @Test
  public void nullEnumOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> ids  = loader.loadClass("demo.Ids");
      Class<?> idsJson = loader.loadClass("demo.internal.IdsJSON");
      Object obj = ids.getConstructor(java.util.UUID.class, loader.loadClass("demo.Color"))
          .newInstance(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), null);
      String json = (String) idsJson.getMethod("toJSON", ids).invoke(null, obj);
      assertEquals(json, "{\"id\":\"00000000-0000-0000-0000-000000000001\"}");
    }
  }
}
