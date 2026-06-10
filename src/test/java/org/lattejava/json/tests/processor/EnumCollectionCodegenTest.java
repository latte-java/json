/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class EnumCollectionCodegenTest {
  static ProcessorHarness.Result coll;

  @BeforeClass
  public void compileOnce() throws Exception {
    coll = ProcessorHarness.compile("collections");
    assertTrue(coll.success(), coll.diagnostics().toString());
  }

  @Test
  public void roundTripsEnumList() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> enumColls = loader.loadClass("demo.EnumColls");
      Class<?> enumCollsJson = loader.loadClass("demo.internal.EnumCollsJSON");
      Class<?> color = loader.loadClass("demo.Color");
      String json = "{\"colors\":[\"RED\",\"BLUE\",\"RED\"],\"palette\":[\"GREEN\"]}";
      Object o = enumCollsJson.getMethod("fromJSON", String.class).invoke(null, json);
      var colors = (java.util.List<?>) enumColls.getMethod("colors").invoke(o);
      Object red = Enum.valueOf(color.asSubclass(Enum.class), "RED");
      Object blue = Enum.valueOf(color.asSubclass(Enum.class), "BLUE");
      assertEquals(colors, java.util.List.of(red, blue, red));
      assertEquals(enumCollsJson.getMethod("toJSON", enumColls).invoke(null, o), json);
    }
  }

  @Test
  public void roundTripsEnumSet() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> enumColls = loader.loadClass("demo.EnumColls");
      Class<?> enumCollsJson = loader.loadClass("demo.internal.EnumCollsJSON");
      Class<?> color = loader.loadClass("demo.Color");
      String json = "{\"colors\":[],\"palette\":[\"GREEN\",\"GREEN\",\"RED\"]}";
      Object o = enumCollsJson.getMethod("fromJSON", String.class).invoke(null, json);
      var palette = enumColls.getMethod("palette").invoke(o);
      assertTrue(palette instanceof java.util.LinkedHashSet, "Set must be LinkedHashSet");
      var paletteSet = (java.util.Set<?>) palette;
      assertEquals(paletteSet.size(), 2, "duplicate [GREEN] must collapse");
      Object green = Enum.valueOf(color.asSubclass(Enum.class), "GREEN");
      Object red = Enum.valueOf(color.asSubclass(Enum.class), "RED");
      assertEquals(new java.util.ArrayList<>(paletteSet), java.util.List.of(green, red),
          "insertion order must be preserved");
      String expectedJson = "{\"colors\":[],\"palette\":[\"GREEN\",\"RED\"]}";
      assertEquals(enumCollsJson.getMethod("toJSON", enumColls).invoke(null, o), expectedJson);
    }
  }
}
