/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolicyCodegenTest {
  static ProcessorHarness.Result policies;

  @BeforeClass
  public void compileOnce() throws Exception {
    policies = ProcessorHarness.compile("policies");
    assertTrue(policies.success(), policies.diagnostics().toString());
  }

  @Test
  public void readOnlySerializedNotDeserialized() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> t = loader.loadClass("demo.Directions");
      Class<?> j = loader.loadClass("demo.internal.DirectionsJSON");
      // readOnly + writeOnly + ignored all parse-absent; only `both` and `readOnly` round on the read side.
      Object o = j.getMethod("fromJSON", String.class)
          .invoke(null, "{\"both\":\"b\",\"readOnly\":\"r\",\"writeOnly\":\"w\",\"ignored\":\"i\"}");
      // readOnly is NOT deserialized -> stays null; writeOnly IS deserialized; ignored is NOT.
      assertEquals(t.getMethod("both").invoke(o), "b");
      assertNull(t.getMethod("readOnly").invoke(o), "readOnly must not be read from input");
      assertEquals(t.getMethod("writeOnly").invoke(o), "w");
      assertNull(t.getMethod("ignored").invoke(o), "ignored must not be read from input");
    }
  }

  @Test
  public void serializeOmitsWriteOnlyAndIgnored() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> t = loader.loadClass("demo.Directions");
      Class<?> j = loader.loadClass("demo.internal.DirectionsJSON");
      Object o = t.getConstructor(String.class, String.class, String.class, String.class)
          .newInstance("b", "r", "w", "i");
      // readOnly IS serialized; writeOnly and ignored are NOT.
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"both\":\"b\",\"readOnly\":\"r\"}");
    }
  }
}
