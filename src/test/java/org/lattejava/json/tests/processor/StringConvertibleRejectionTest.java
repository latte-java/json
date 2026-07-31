/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Compile-time rejections for {@code @JSONField(asString = true)}. The opt-in exists precisely so that a type with an
 * unrelated single-String constructor is never bound by accident, so the contract is checked, not inferred.
 *
 * @author Brian Pontarelli
 */
public class StringConvertibleRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void missingStringConstructorRejected() throws Exception {
    assertFailsWith("badasstring_nostringctor", "asString", "[tag]", "constructor taking a single String");
  }

  @Test public void missingToStringRejected() throws Exception {
    assertFailsWith("badasstring_notostring", "asString", "[tag]", "declare toString()");
  }

  @Test public void alreadySupportedTypeRejected() throws Exception {
    assertFailsWith("badasstring_supported", "asString", "[id]", "already supported");
  }

  @Test public void collectionTypeRejected() throws Exception {
    assertFailsWith("badasstring_collection", "asString", "[tags]", "not its elements");
  }

  @Test public void combinedWithFormatRejected() throws Exception {
    assertFailsWith("badasstring_format", "asString with format", "[when]");
  }

  @Test public void unannotatedTypeStillRejected() throws Exception {
    // The structural predicate must not become auto-detection: demo.SemVer satisfies the contract, but the member
    // in this fixture does not opt in, so it stays an unsupported-type error.
    assertFailsWith("badasstring_nooptin", "[version]", "not @JSON-annotated");
  }
}
