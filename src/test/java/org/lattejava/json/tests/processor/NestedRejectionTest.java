/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class NestedRejectionTest {
  @Test
  public void directNonJSONRecordRejected() throws Exception {
    var r = ProcessorHarness.compile("badnested");
    assertFalse(r.success(), "a nested record without @JSON must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("not @JSON-annotated") && d.contains("[p]")),
        "expected a not-@JSON-annotated error for [p], got: " + r.diagnostics());
  }

  @Test
  public void listOfNonJSONRecordRejected() throws Exception {
    var r = ProcessorHarness.compile("badnested");
    assertFalse(r.success());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("not @JSON-annotated") && d.contains("[ps]")),
        "expected a not-@JSON-annotated error for [ps], got: " + r.diagnostics());
  }
}
