/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class DynamicMapRejectionTest {
  @Test
  public void nonStringKeyWithObjectValueRejected() throws Exception {
    var r = ProcessorHarness.compile("baddynamicmap");
    assertFalse(r.success(), "Map<Integer, Object> must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("Map key") && d.contains("[m]")),
        "expected Map-key error for [m], got: " + r.diagnostics());
  }
}
