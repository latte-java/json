/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class RawRejectionTest {
  static void assertFailsWith(String fixture, String... needles) throws Exception {
    var r = ProcessorHarness.compile(fixture);
    assertFalse(r.success(), fixture + " must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            Arrays.stream(needles).allMatch(d::contains)),
        "expected " + Arrays.toString(needles) + ", got: " + r.diagnostics());
  }

  @Test public void nonStringRejected() throws Exception {
    assertFailsWith("badraw_type", "@JSONRaw member", "must be of type String", "[bad]");
  }

  @Test public void twoRawMembersRejected() throws Exception {
    assertFailsWith("badraw_two", "at most one", "@JSONRaw");
  }

  @Test public void rawWithFieldRejected() throws Exception {
    assertFailsWith("badraw_field", "cannot also be annotated @JSONField", "[m]");
  }

  @Test public void rawWithCatchAllRejected() throws Exception {
    assertFailsWith("badraw_catchall", "cannot also be annotated @JSONCatchAll", "[m]");
  }

  @Test public void beanRawWithoutWriterRejected() throws Exception {
    assertFailsWith("badraw_nowriter", "@JSONRaw member", "no usable writer", "[raw]");
  }

  // @JSONCatchAll on the backing field and @JSONRaw on the getter of the same bean property: the two annotations
  // sit on different physical elements, so the conflict must still be caught rather than silently dropping the
  // @JSONRaw and treating the property as a plain catch-all.
  @Test public void beanSplitPlacementRejected() throws Exception {
    assertFailsWith("badraw_beansplit", "cannot also be annotated @JSONCatchAll", "[extra]");
  }

  // ClassValidator.validateBean's raw branch, not AbstractValidator's: a bean @JSONRaw property whose type is not
  // String must be rejected on the bean path too, not just for records/@JSONConstructor classes.
  @Test public void beanNonStringRawRejected() throws Exception {
    assertFailsWith("badraw_beantype", "@JSONRaw member", "[raw]", "must be of type String", "[int]");
  }

  // Bean-path equivalent of rawWithFieldRejected: a bean property carrying both @JSONRaw and @JSONField.
  @Test public void beanRawWithFieldRejected() throws Exception {
    assertFailsWith("badraw_beanfield", "@JSONRaw member", "[raw]", "cannot also be annotated @JSONField");
  }

  // Bean-path equivalent of twoRawMembersRejected: a bean declaring two @JSONRaw properties.
  @Test public void beanTwoRawMembersRejected() throws Exception {
    assertFailsWith("badraw_beantwo", "[demo.BeanTwo]", "declares [2] @JSONRaw members", "at most one is allowed");
  }
}
