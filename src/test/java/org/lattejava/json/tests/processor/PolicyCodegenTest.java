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

  @Test
  public void formatRoundTripsCustomPatterns() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> t = loader.loadClass("demo.Times");
      Class<?> j = loader.loadClass("demo.internal.TimesJSON");
      String json = "{\"date\":\"03/14/2026\",\"stamp\":\"2026-03-14T09:26:53\","
          + "\"millis\":1741944413000,\"seconds\":1741944413}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("date").invoke(o), java.time.LocalDate.of(2026, 3, 14));
      assertEquals(t.getMethod("millis").invoke(o), java.time.Instant.ofEpochMilli(1741944413000L));
      assertEquals(t.getMethod("seconds").invoke(o), java.time.Instant.ofEpochSecond(1741944413L));
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), json);
    }
  }

  @Test
  public void epochInstantsAreJSONIntegers() throws Exception {
    try (var loader = (URLClassLoader) policies.loader()) {
      Class<?> j = loader.loadClass("demo.internal.TimesJSON");
      Class<?> t = loader.loadClass("demo.Times");
      Object o = t.getConstructor(java.time.LocalDate.class, java.time.LocalDateTime.class,
              java.time.Instant.class, java.time.Instant.class)
          .newInstance(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0),
              java.time.Instant.ofEpochMilli(1000L), java.time.Instant.ofEpochSecond(2L));
      String json = (String) j.getMethod("toJSON", t).invoke(null, o);
      assertTrue(json.contains("\"millis\":1000"), "epoch millis must be a bare integer, got: " + json);
      assertTrue(json.contains("\"seconds\":2"), "epoch seconds must be a bare integer, got: " + json);
    }
  }
}
