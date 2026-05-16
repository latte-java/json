/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

public class HelperTemplateDriftTest {
  private static final List<String> HELPERS = List.of(
      "JSONProcessingException", "JSONObjectHandler", "JSONObserver",
      "JSONArrayObserver", "JSONPolymorphicObserver", "JSONParser",
      "JSONBuilder", "Numbers", "SkipObserver", "SkipArrayObserver",
      "AnyObjectObserver", "AnyArrayObserver");

  @Test
  public void everyHelperHasATemplate() {
    for (String name : HELPERS) {
      Path tmpl = Path.of("src/main/resources/org/lattejava/json/internal-templates/" + name + ".java.txt");
      assertTrue(Files.exists(tmpl), "Missing template for [" + name + "] at [" + tmpl + "]");
    }
  }

  @Test
  public void templateIsByteIdenticalToCanonicalSource() throws Exception {
    for (String name : HELPERS) {
      String canonical = Files.readString(
          Path.of("src/main/java/org/lattejava/json/" + name + ".java"), StandardCharsets.UTF_8);
      String template = Files.readString(
          Path.of("src/main/resources/org/lattejava/json/internal-templates/" + name + ".java.txt"),
          StandardCharsets.UTF_8);
      assertEquals(template, canonical,
          "Template for [" + name + "] has drifted from the canonical source; "
          + "regenerate src/main/resources/org/lattejava/json/internal-templates/" + name + ".java.txt");
    }
  }

  @Test
  public void canonicalSourcesDeclareTheRewritablePackage() throws Exception {
    for (String name : HELPERS) {
      String canonical = Files.readString(
          Path.of("src/main/java/org/lattejava/json/" + name + ".java"), StandardCharsets.UTF_8);
      assertTrue(canonical.contains("\npackage org.lattejava.json;\n"),
          "[" + name + "] must contain exactly the line 'package org.lattejava.json;' "
          + "for the processor to rewrite it");
    }
  }
}
