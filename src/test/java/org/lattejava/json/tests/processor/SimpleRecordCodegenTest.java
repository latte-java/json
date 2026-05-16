/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class SimpleRecordCodegenTest {

  private static ProcessorHarness.Result simple;

  @BeforeClass
  public void compileOnce() throws Exception {
    simple = ProcessorHarness.compile("simple");
    assertTrue(simple.success(), "fixture compile must succeed; " + simple.diagnostics());
  }

  @Test
  public void serializesUserToJSONStringInDeclarationOrder() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object user = userClass.getConstructor(String.class, int.class, String.class)
          .newInstance("Alice", 30, "alice@example.com");
      String json = (String) userJson.getMethod("toJSON", userClass).invoke(null, user);
      assertEquals(json, "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}");
    }
  }

  @Test
  public void toJSONBytesMatchesToJSON() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object user = userClass.getConstructor(String.class, int.class, String.class)
          .newInstance("Bob", 1, "b@x.io");
      String s = (String) userJson.getMethod("toJSON", userClass).invoke(null, user);
      byte[] b = (byte[]) userJson.getMethod("toJSONBytes", userClass).invoke(null, user);
      assertEquals(new String(b, StandardCharsets.UTF_8), s);
    }
  }

  @Test
  public void omitsNullStringByDefault() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object user = userClass.getConstructor(String.class, int.class, String.class)
          .newInstance("Cara", 7, null);
      String json = (String) userJson.getMethod("toJSON", userClass).invoke(null, user);
      assertEquals(json, "{\"name\":\"Cara\",\"age\":7}");
    }
  }

  @Test
  public void serializesAllPrimitiveKinds() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> p     = loader.loadClass("demo.Primitives");
      Class<?> pJson = loader.loadClass("demo.internal.PrimitivesJSON");
      Object obj = p.getConstructor(boolean.class, byte.class, short.class, int.class,
              long.class, float.class, double.class, Integer.class, Long.class)
          .newInstance(true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.25d, 7, 8L);
      String json = (String) pJson.getMethod("toJSON", p).invoke(null, obj);
      assertEquals(json,
          "{\"flag\":true,\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5.5,\"d\":6.25,\"boxedInt\":7,\"boxedLong\":8}");
    }
  }

  @Test
  public void omitsNullBoxedFieldsByDefault() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> p     = loader.loadClass("demo.Primitives");
      Class<?> pJson = loader.loadClass("demo.internal.PrimitivesJSON");
      Object obj = p.getConstructor(boolean.class, byte.class, short.class, int.class,
              long.class, float.class, double.class, Integer.class, Long.class)
          .newInstance(true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.25d, null, null);
      String json = (String) pJson.getMethod("toJSON", p).invoke(null, obj);
      assertEquals(json, "{\"flag\":true,\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5.5,\"d\":6.25}");
    }
  }
}
