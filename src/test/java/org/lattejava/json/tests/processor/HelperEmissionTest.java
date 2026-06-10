/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

public class HelperEmissionTest {
  @Test
  public void helpersEmittedIntoModuleInternalAndCompile() throws Exception {
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(),
        "fixture compile (with helper emission) must succeed; diagnostics: " + result.diagnostics());

    // demo.simple module → helpers land in package demo.simple.internal
    for (String helper : org.lattejava.json.JSONProcessor.HELPERS) {
      Path cls = result.outputDir().resolve("demo/simple/internal/" + helper + ".class");
      assertTrue(Files.exists(cls),
          "expected emitted+compiled helper [" + cls + "]");
    }
  }

  @Test
  public void emittedHelperHasRewrittenPackage() throws Exception {
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(), result.diagnostics().toString());
    Path src = result.outputDir().resolve("demo/simple/internal/JSONParser.java");
    assertTrue(Files.exists(src), "expected emitted JSONParser.java source");
    String text = Files.readString(src, StandardCharsets.UTF_8);
    assertTrue(text.contains("package demo.simple.internal;"),
        "emitted helper must have rewritten package; head was:\n"
        + text.substring(0, Math.min(200, text.length())));
    assertFalse(text.contains("package org.lattejava.json;"),
        "original package line must be gone");
  }

  @Test
  public void helpersEmittedOnlyOnce() throws Exception {
    // simple fixture has two @JSON records; helpers must be emitted exactly once (no Filer dup error)
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(),
        "duplicate helper emission would surface as a Filer error; diagnostics: "
        + result.diagnostics());
  }
}
