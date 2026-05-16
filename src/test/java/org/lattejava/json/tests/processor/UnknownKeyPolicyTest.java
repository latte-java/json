/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class UnknownKeyPolicyTest {
  @Test
  public void lenientByDefaultDropsUnknownScalarKeys() throws Exception {
    var r = ProcessorHarness.compile("simple");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object parsed = userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Z\",\"age\":1,\"email\":\"z@z\",\"extra\":\"ignored\",\"more\":5}");
      assertEquals(userClass.getMethod("name").invoke(parsed), "Z");
      assertEquals(userClass.getMethod("age").invoke(parsed), 1);
      assertEquals(userClass.getMethod("email").invoke(parsed), "z@z");
    }
  }

  @Test
  public void strictAcceptsExplicitNullForKnownNullableField() throws Exception {
    var r = ProcessorHarness.compile("strict");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> sc = loader.loadClass("demo.StrictUser");
      Class<?> sj = loader.loadClass("demo.internal.StrictUserJSON");
      Object parsed = sj.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":null,\"age\":1}");
      assertNull(sc.getMethod("name").invoke(parsed),
          "explicit null for known nullable String field must set null, not throw");
      assertEquals(sc.getMethod("age").invoke(parsed), 1);
    }
  }

  @Test
  public void strictRejectsUnknownKey() throws Exception {
    var r = ProcessorHarness.compile("strict");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> sj = loader.loadClass("demo.internal.StrictUserJSON");
      try {
        sj.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Z\",\"age\":1,\"surprise\":true}");
        fail("strict mode must reject an unknown key");
      } catch (java.lang.reflect.InvocationTargetException e) {
        Throwable cause = e.getCause();
        assertNotNull(cause, "expected a cause");
        assertEquals(cause.getClass().getSimpleName(), "JSONProcessingException",
            "strict unknown-key must surface as JSONProcessingException, got: " + cause);
        assertTrue(cause.getMessage().contains("Unknown JSON key")
                && cause.getMessage().contains("surprise"),
            "message should name the unknown key; was: " + cause.getMessage());
      }
    }
  }

  @Test
  public void strictAcceptsExactKeys() throws Exception {
    var r = ProcessorHarness.compile("strict");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> sc = loader.loadClass("demo.StrictUser");
      Class<?> sj = loader.loadClass("demo.internal.StrictUserJSON");
      Object parsed = sj.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Z\",\"age\":1}");
      assertEquals(sc.getMethod("name").invoke(parsed), "Z");
      assertEquals(sc.getMethod("age").invoke(parsed), 1);
    }
  }
}
