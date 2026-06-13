/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class CollectionRejectionTest {
  @Test
  public void nonStringFormMapKeyRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("m")),
        "expected Map-key error for [m], got: " + r.diagnostics());
  }

  @Test
  public void rawCollectionRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "raw collection member must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("raw or wildcard") && d.contains("[raw]")),
        "expected raw-collection error for [raw], got: " + r.diagnostics());
  }

  @Test
  public void wildcardCollectionRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "unbounded-wildcard collection member must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("element type") && d.contains("anySet")),
        "expected unsupported-element error for [anySet], got: " + r.diagnostics());
  }
}
