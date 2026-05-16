/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ProcessorErrorsTest {
  @Test
  public void nonRecordIsRejected() throws Exception {
    var result = ProcessorHarness.compile("notarecord");
    assertFalse(result.success(), "compilation must fail for a non-record @JSON type");
    assertTrue(result.diagnostics().stream()
            .anyMatch(d -> d.contains("only records") && d.contains("[demo.NotARecord]")),
        "expected a 'only records' error mentioning [demo.NotARecord], got: " + result.diagnostics());
  }

  @Test
  public void unsupportedComponentTypeIsRejected() throws Exception {
    var result = ProcessorHarness.compile("badtype");
    assertFalse(result.success());
    assertTrue(result.diagnostics().stream()
            .anyMatch(d -> d.contains("unsupported") && d.contains("tags")),
        "expected an unsupported-type error mentioning [tags], got: " + result.diagnostics());
  }

  @Test
  public void missingModuleIsRejected() throws Exception {
    var result = ProcessorHarness.compile("nomodule");
    assertFalse(result.success());
    assertTrue(result.diagnostics().stream()
            .anyMatch(d -> d.contains("named module")),
        "expected a named-module error, got: " + result.diagnostics());
  }
}
