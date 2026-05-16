/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class BigNumberCodegenTest {
  static ProcessorHarness.Result extras;

  @BeforeClass
  public void compileOnce() throws Exception {
    extras = ProcessorHarness.compile("extras");
    assertTrue(extras.success(),
        "extras fixture must compile (all new types supported); diagnostics: "
        + extras.diagnostics());
  }

  @Test
  public void companionsGeneratedForAllExtras() {
    for (String n : new String[]{"BagJSON", "IdsJSON", "TimesJSON"}) {
      assertTrue(java.nio.file.Files.exists(
          extras.outputDir().resolve("demo/internal/" + n + ".class")),
          "expected generated companion [" + n + "]");
    }
  }
}
