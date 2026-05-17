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
  public void nestedCollectionRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "nested collection / bad key must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("nested collection") && d.contains("deep")),
        "expected nested-collection error for [deep], got: " + r.diagnostics());
  }

  @Test
  public void nonStringFormMapKeyRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("m")),
        "expected Map-key error for [m], got: " + r.diagnostics());
  }

  @Test
  public void collectionOfJSONElementRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "collection whose element is an @JSON type must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("unsupported") && d.contains("element type") && d.contains("items")),
        "expected unsupported-element-type error for [items], got: " + r.diagnostics());
  }

  @Test
  public void rawOrWildcardCollectionRejected() throws Exception {
    var r = ProcessorHarness.compile("badcollections");
    assertFalse(r.success(), "raw / unbounded-wildcard collection component must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("nested collection") && d.contains("raw")),
        "expected nested-collection error for raw [raw], got: " + r.diagnostics());
  }
}
