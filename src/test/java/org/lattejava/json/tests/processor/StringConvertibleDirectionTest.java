/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The string-convertible contract is per-direction: a member that is never deserialized never calls the String
 * constructor, and one that is never serialized never calls toString(), so neither should be required.
 *
 * @author Brian Pontarelli
 */
public class StringConvertibleDirectionTest {
  static ProcessorHarness.Result direction;

  @BeforeClass
  public void compileOnce() throws Exception {
    direction = ProcessorHarness.compile("stringconv_direction");
    assertTrue(direction.success(), direction.diagnostics().toString());
  }

  @Test
  public void readOnlyMemberNeedsOnlyToString() throws Exception {
    try (var loader = (URLClassLoader) direction.loader()) {
      Class<?> type = loader.loadClass("demo.Directional");
      Class<?> companion = loader.loadClass("demo.internal.DirectionalJSON");
      Object obj = type.getConstructor(loader.loadClass("demo.OnlyToString"), loader.loadClass("demo.OnlyCtor"))
          .newInstance(loader.loadClass("demo.OnlyToString").getConstructor(int.class).newInstance(42), null);
      assertEquals(companion.getMethod("toJSON", type).invoke(null, obj), "{\"out\":\"42\"}");
    }
  }

  @Test
  public void writeOnlyMemberNeedsOnlyTheConstructor() throws Exception {
    try (var loader = (URLClassLoader) direction.loader()) {
      Class<?> type = loader.loadClass("demo.Directional");
      Class<?> onlyCtor = loader.loadClass("demo.OnlyCtor");
      Class<?> companion = loader.loadClass("demo.internal.DirectionalJSON");
      Object obj = companion.getMethod("fromJSON", String.class).invoke(null, "{\"in\":\"hello\"}");
      Object in = type.getMethod("in").invoke(obj);
      assertSame(in.getClass(), onlyCtor);
      assertEquals(onlyCtor.getMethod("value").invoke(in), "hello");
    }
  }

  @Test
  public void getterOnlyBeanPropertyNeedsOnlyToString() throws Exception {
    try (var loader = (URLClassLoader) direction.loader()) {
      Class<?> type = loader.loadClass("demo.GetterOnly");
      Class<?> companion = loader.loadClass("demo.internal.GetterOnlyJSON");
      Object obj = type.getConstructor().newInstance();
      assertEquals(companion.getMethod("toJSON", type).invoke(null, obj), "{\"label\":\"7\"}");
    }
  }
}
