/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class NestedCodegenTest {
  static ProcessorHarness.Result nested;

  @BeforeClass
  public void compileOnce() throws Exception {
    nested = ProcessorHarness.compile("nested");
    assertTrue(nested.success(), nested.diagnostics().toString());
  }

  @Test
  public void nestedFieldRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},\"prior\":[],\"seen\":[],\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void listOfNestedRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},"
          + "\"prior\":[{\"street\":\"2 Oak\",\"city\":\"Boulder\",\"geo\":{\"lat\":3.0,\"lng\":4.0}}],"
          + "\"seen\":[],\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void setOfNestedRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},\"prior\":[],"
          + "\"seen\":[{\"street\":\"3 Pine\",\"city\":\"Aspen\",\"geo\":{\"lat\":5.0,\"lng\":6.0}}],"
          + "\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void mapValueNestedRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"address\":{\"street\":\"1 Main\",\"city\":\"Denver\","
          + "\"geo\":{\"lat\":1.5,\"lng\":2.5}},\"prior\":[],\"seen\":[],"
          + "\"byType\":{\"HOME\":{\"street\":\"4 Elm\",\"city\":\"Vail\",\"geo\":{\"lat\":7.0,\"lng\":8.0}}}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void recursionRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> treeJson = loader.loadClass("demo.internal.TreeJSON");
      Class<?> tree = loader.loadClass("demo.Tree");
      String json = "{\"name\":\"root\",\"kids\":[{\"name\":\"a\",\"kids\":[]},"
          + "{\"name\":\"b\",\"kids\":[{\"name\":\"b1\",\"kids\":[]}]}]}";
      Object o = treeJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(treeJson.getMethod("toJSON", tree).invoke(null, o), json);
    }
  }

  @Test
  public void nullNestedFieldOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      Class<?> user = loader.loadClass("demo.User");
      String json = "{\"name\":\"Bob\",\"prior\":[],\"seen\":[],\"byType\":{}}";
      Object o = userJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(userJson.getMethod("toJSON", user).invoke(null, o), json);
    }
  }

  @Test
  public void nullNestedFieldEmittedWhenOmitNullsFalse() throws Exception {
    try (var loader = (URLClassLoader) nested.loader()) {
      Class<?> looseJson = loader.loadClass("demo.internal.LooseJSON");
      Class<?> loose = loader.loadClass("demo.Loose");
      String json = "{\"name\":\"Bob\",\"address\":null}";
      Object o = looseJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(looseJson.getMethod("toJSON", loose).invoke(null, o), json);
    }
  }
}
