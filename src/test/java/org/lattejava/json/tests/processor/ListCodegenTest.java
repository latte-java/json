/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ListCodegenTest {
  static ProcessorHarness.Result coll;

  @BeforeClass
  public void compileOnce() throws Exception {
    coll = ProcessorHarness.compile("collections");
    assertTrue(coll.success(),
        "collections fixture must compile; diagnostics: " + coll.diagnostics());
  }

  @Test
  public void companionsGenerated() {
    for (String n : new String[]{"ListsJSON", "SetsJSON", "MapsJSON"}) {
      assertTrue(java.nio.file.Files.exists(
          coll.outputDir().resolve("demo/internal/" + n + ".class")),
          "expected companion [" + n + "]");
    }
  }

  @Test
  public void roundTripsLists() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> lists = loader.loadClass("demo.Lists");
      Class<?> listsJson = loader.loadClass("demo.internal.ListsJSON");
      String json = "{\"tags\":[\"a\",\"b\"],\"nums\":[1,2,3],"
          + "\"ids\":[\"00000000-0000-0000-0000-000000000001\"]}";
      Object o = listsJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(lists.getMethod("tags").invoke(o), java.util.List.of("a", "b"));
      assertEquals(lists.getMethod("nums").invoke(o), java.util.List.of(1, 2, 3));
      assertEquals(listsJson.getMethod("toJSON", lists).invoke(null, o), json);
    }
  }

  @Test
  public void emptyListRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> lists = loader.loadClass("demo.Lists");
      Class<?> listsJson = loader.loadClass("demo.internal.ListsJSON");
      Object o = listsJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"tags\":[],\"nums\":[],\"ids\":[]}");
      assertEquals(lists.getMethod("tags").invoke(o), java.util.List.of());
      assertEquals(listsJson.getMethod("toJSON", lists).invoke(null, o),
          "{\"tags\":[],\"nums\":[],\"ids\":[]}");
    }
  }

  @Test
  public void nullListOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) coll.loader()) {
      Class<?> lists = loader.loadClass("demo.Lists");
      Class<?> listsJson = loader.loadClass("demo.internal.ListsJSON");
      Object o = lists.getConstructor(java.util.List.class, java.util.List.class, java.util.List.class)
          .newInstance(java.util.List.of("x"), null, null);
      assertEquals(listsJson.getMethod("toJSON", lists).invoke(null, o), "{\"tags\":[\"x\"]}");
    }
  }
}
