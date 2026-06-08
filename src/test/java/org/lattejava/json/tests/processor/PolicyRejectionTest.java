/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolicyRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void readOnlyWriteOnlyConflictRejected() throws Exception {
    assertFailsWith("badpolicy_rwconflict", "readOnly and writeOnly", "[x]");
  }

  @Test public void ignorePlusOtherRejected() throws Exception {
    assertFailsWith("badpolicy_ignoreplus", "ignore", "[value]");
  }
}
