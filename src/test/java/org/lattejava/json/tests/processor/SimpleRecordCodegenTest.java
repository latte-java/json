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

  @Test
  public void roundTripsUser() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object parsed = userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}");
      assertEquals(userClass.getMethod("name").invoke(parsed), "Alice");
      assertEquals(userClass.getMethod("age").invoke(parsed), 30);
      assertEquals(userClass.getMethod("email").invoke(parsed), "alice@example.com");
      String reser = (String) userJson.getMethod("toJSON", userClass).invoke(null, parsed);
      assertEquals(reser, "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}");
    }
  }

  @Test
  public void roundTripsAllPrimitiveKinds() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> p     = loader.loadClass("demo.Primitives");
      Class<?> pJson = loader.loadClass("demo.internal.PrimitivesJSON");
      String json = "{\"flag\":true,\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5.5,\"d\":6.25,\"boxedInt\":7,\"boxedLong\":8}";
      Object obj = pJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(p.getMethod("flag").invoke(obj), true);
      assertEquals(p.getMethod("b").invoke(obj), (byte) 1);
      assertEquals(p.getMethod("s").invoke(obj), (short) 2);
      assertEquals(p.getMethod("i").invoke(obj), 3);
      assertEquals(p.getMethod("l").invoke(obj), 4L);
      assertEquals(p.getMethod("f").invoke(obj), 5.5f);
      assertEquals(p.getMethod("d").invoke(obj), 6.25d);
      assertEquals(p.getMethod("boxedInt").invoke(obj), 7);
      assertEquals(p.getMethod("boxedLong").invoke(obj), 8L);
      assertEquals(pJson.getMethod("toJSON", p).invoke(null, obj), json);
    }
  }

  @Test
  public void missingPrimitiveKeyLeavesJavaDefault() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object parsed = userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"OnlyName\"}");
      assertEquals(userClass.getMethod("name").invoke(parsed), "OnlyName");
      assertEquals(userClass.getMethod("age").invoke(parsed), 0);
      assertNull(userClass.getMethod("email").invoke(parsed));
    }
  }

  @Test(expectedExceptions = java.lang.reflect.InvocationTargetException.class)
  public void intOverflowThrows() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"X\",\"age\":99999999999999999999}");
    }
  }
}
