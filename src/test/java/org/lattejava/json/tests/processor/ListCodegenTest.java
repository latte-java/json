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
}
