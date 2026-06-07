/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolyRejectionTest {
  private static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            java.util.Arrays.stream(needles).allMatch(d::contains)),
        "expected a diagnostic containing " + java.util.Arrays.toString(needles)
            + ", got: " + r.diagnostics());
  }

  @Test public void nonSealedRejected() throws Exception {
    assertFailsWith("badpoly_nonsealed", "sealed", "NonSealed");
  }

  @Test public void subtypeMissingJSONRejected() throws Exception {
    assertFailsWith("badpoly_missingjson", "@JSON", "Impl");
  }

  @Test public void duplicateDiscriminatorValueRejected() throws Exception {
    assertFailsWith("badpoly_dupvalue", "discriminator value", "same");
  }

  @Test public void discriminatorCollisionRejected() throws Exception {
    assertFailsWith("badpoly_collision", "discriminator", "kind");
  }

  @Test public void orphanSubtypeRejected() throws Exception {
    assertFailsWith("badpoly_orphan", "@JSONSubtype", "@JSONTypeInfo");
  }

  @Test public void interfaceWithoutTypeInfoRejected() throws Exception {
    assertFailsWith("badpoly_notypeinfo", "@JSONTypeInfo", "Bare");
  }

  @Test public void nonRecordSubtypeRejected() throws Exception {
    assertFailsWith("badpoly_nonrecordsub", "must be a record", "Mid");
  }
}
