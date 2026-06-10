/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ClassRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void twoConstructorsRejected() throws Exception {
    assertFailsWith("badclass_twoctor", "exactly one is allowed", "TwoCtor");
  }

  @Test public void noReaderRejected() throws Exception {
    assertFailsWith("badclass_noreader", "no usable reader", "[secret]");
  }

  @Test public void constructorOnRecordRejected() throws Exception {
    assertFailsWith("badrecord_jsonctor", "@JSONConstructor on record", "redundant");
  }

  @Test public void nonPublicConstructorRejected() throws Exception {
    assertFailsWith("badclass_privatector", "must be public", "PrivateCtor");
  }
}
