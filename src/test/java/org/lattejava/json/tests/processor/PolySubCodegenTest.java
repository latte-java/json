/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolySubCodegenTest {
  static ProcessorHarness.Result polysub;

  @BeforeClass
  public void compileOnce() throws Exception {
    polysub = ProcessorHarness.compile("polysub");
    assertTrue(polysub.success(), polysub.diagnostics().toString());
  }

  @Test
  public void recordSubtypeRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> shape = loader.loadClass("demo.Shape");
      Class<?> shapeJson = loader.loadClass("demo.internal.ShapeJSON");
      String json = "{\"kind\":\"circle\",\"radius\":3}";
      Object o = shapeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Circle").getMethod("radius").invoke(o), 3);
      assertEquals(shapeJson.getMethod("toJSON", shape).invoke(null, o), json);
    }
  }

  @Test
  public void constructorClassSubtypeRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> shape = loader.loadClass("demo.Shape");
      Class<?> shapeJson = loader.loadClass("demo.internal.ShapeJSON");
      String json = "{\"kind\":\"square\",\"side\":2}";
      Object o = shapeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Square").getMethod("getSide").invoke(o), 2);
      assertEquals(shapeJson.getMethod("toJSON", shape).invoke(null, o), json);
    }
  }

  @Test
  public void beanSubtypeRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> shape = loader.loadClass("demo.Shape");
      Class<?> shapeJson = loader.loadClass("demo.internal.ShapeJSON");
      String json = "{\"kind\":\"note\",\"text\":\"hi\"}";
      Object o = shapeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Note").getMethod("getText").invoke(o), "hi");
      assertEquals(shapeJson.getMethod("toJSON", shape).invoke(null, o), json);
    }
  }

  @Test
  public void classSubtypeNestedInRecord() throws Exception {
    try (var loader = (URLClassLoader) polysub.loader()) {
      Class<?> drawing = loader.loadClass("demo.Drawing");
      Class<?> drawingJson = loader.loadClass("demo.internal.DrawingJSON");
      String json = "{\"title\":\"t\",\"shape\":{\"kind\":\"square\",\"side\":2}}";
      Object o = drawingJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(drawingJson.getMethod("toJSON", drawing).invoke(null, o), json);
    }
  }
}
