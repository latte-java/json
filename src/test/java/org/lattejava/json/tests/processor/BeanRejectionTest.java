/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class BeanRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void noPublicNoArgCtorRejected() throws Exception {
    assertFailsWith("badbean_noctor", "requires a public no-arg constructor", "NoCtor");
  }

  @Test public void emptyBeanRejected() throws Exception {
    assertFailsWith("badbean_empty", "no serializable properties", "Empty");
  }

  @Test public void noAccessorPropertyRejected() throws Exception {
    assertFailsWith("badbean_noaccessor", "neither a usable reader nor writer", "[secret]");
  }

  @Test public void unsupportedPropertyTypeRejected() throws Exception {
    assertFailsWith("badbean_unsupported", "unsupported type", "[worker]");
  }

  @Test public void conflictingDirectionRejected() throws Exception {
    assertFailsWith("badbean_conflictdir", "neither serialized nor deserialized", "[computed]");
  }
}
