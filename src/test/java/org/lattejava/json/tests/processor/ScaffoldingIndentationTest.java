/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

public class ScaffoldingIndentationTest {
  @Test
  public void observerMethodsAndCaseArmsAreNotOverIndented() throws Exception {
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(), "fixture must compile: " + result.diagnostics());

    Path companion;
    try (var walk = Files.walk(result.outputDir())) {
      companion = walk.filter(p -> p.getFileName().toString().endsWith("JSON.java"))
                      .filter(p -> !p.getFileName().toString().equals("JSONProcessor.java"))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no generated *JSON.java companion found"));
    }
    String src = Files.readString(companion, StandardCharsets.UTF_8);

    // Observer callbacks are class members: exactly 2-space indent, switch at 4, case at 6.
    assertTrue(src.contains("\n  @Override public void string(String key, String value) {\n"),
        "string(...) observer must be at 2-space indent; companion was:\n" + src);
    assertTrue(src.contains("\n    switch (key) {\n"),
        "switch must be at 4-space indent; companion was:\n" + src);
    // The over-indent bug produced this 4-space signature — it must NOT appear.
    assertFalse(src.contains("\n    @Override public void string(String key, String value) {\n"),
        "string(...) observer is over-indented (4 spaces); companion was:\n" + src);
  }
}
