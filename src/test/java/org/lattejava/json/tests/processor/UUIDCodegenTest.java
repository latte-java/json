/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class UUIDCodegenTest {
  static ProcessorHarness.Result extras;

  @BeforeClass
  public void compileOnce() throws Exception {
    extras = ProcessorHarness.compile("extras");
    assertTrue(extras.success(), extras.diagnostics().toString());
  }

  @Test
  public void roundTripsUUID() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> ids  = loader.loadClass("demo.Ids");
      Class<?> idsJson = loader.loadClass("demo.internal.IdsJSON");
      String json = "{\"id\":\"12345678-1234-1234-1234-123456789abc\",\"color\":\"RED\"}";
      Object obj = idsJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(ids.getMethod("id").invoke(obj).toString(),
          "12345678-1234-1234-1234-123456789abc");
      assertEquals(idsJson.getMethod("toJSON", ids).invoke(null, obj), json);
    }
  }

  @Test
  public void malformedUUIDThrowsJSONProcessingException() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> idsJson = loader.loadClass("demo.internal.IdsJSON");
      try {
        idsJson.getMethod("fromJSON", String.class)
            .invoke(null, "{\"id\":\"not-a-uuid\",\"color\":\"RED\"}");
        fail("malformed UUID must throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException",
            "expected JSONProcessingException, got: " + e.getCause());
      }
    }
  }

  @Test
  public void nullUUIDOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> ids  = loader.loadClass("demo.Ids");
      Class<?> idsJson = loader.loadClass("demo.internal.IdsJSON");
      Object obj = ids.getConstructor(java.util.UUID.class, loader.loadClass("demo.Color"))
          .newInstance(null, Enum.valueOf((Class) loader.loadClass("demo.Color"), "BLUE"));
      String json = (String) idsJson.getMethod("toJSON", ids).invoke(null, obj);
      assertEquals(json, "{\"color\":\"BLUE\"}",
          "null UUID must be omitted, not serialized as the string \"null\"");
    }
  }
}
