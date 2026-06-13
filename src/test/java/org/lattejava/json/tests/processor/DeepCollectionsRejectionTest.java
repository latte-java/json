/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DeepCollectionsRejectionTest {
  @Test
  public void nestedObjectValueTypesRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_objectvalue");
    assertFalse(r.success(), "Object leaves inside collections must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("[java.lang.Object]") && d.contains("nestedDynamic")),
        "expected nested-dynamic-map rejection for [nestedDynamic], got: " + r.diagnostics());
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("[java.lang.Object]") && d.contains("anyList")),
        "expected List<Object> rejection for [anyList], got: " + r.diagnostics());
  }

  @Test
  public void nonStringFormKeyAtDepthRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_key");
    assertFalse(r.success(), "Map<Integer, ...> at depth must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("[java.lang.Integer]") && d.contains("byNumber")),
        "expected deep Map-key error for [byNumber], got: " + r.diagnostics());
  }

  @Test
  public void rawCollectionAtDepthRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_raw");
    assertFalse(r.success(), "raw List as a Map value must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("raw or wildcard List") && d.contains("rawList")),
        "expected raw-at-depth error for [rawList], got: " + r.diagnostics());
  }

  @Test
  public void unannotatedRecordAtDepthRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_plain");
    assertFalse(r.success(), "un-annotated record leaf at depth must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("[demo.Plain]") && d.contains("not @JSON-annotated")),
        "expected not-@JSON-annotated error for [demo.Plain], got: " + r.diagnostics());
  }

  @Test
  public void wildcardElementAtDepthRejected() throws Exception {
    var r = ProcessorHarness.compile("baddeep_raw");
    assertFalse(r.success(), "List<List<?>> must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("element type [?]") && d.contains("wildGrid")),
        "expected wildcard-element error for [wildGrid], got: " + r.diagnostics());
  }
}
