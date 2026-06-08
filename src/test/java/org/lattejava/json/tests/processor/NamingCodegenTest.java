/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class NamingCodegenTest {
  static ProcessorHarness.Result naming;

  @BeforeClass
  public void compileOnce() throws Exception {
    naming = ProcessorHarness.compile("naming");
    assertTrue(naming.success(), naming.diagnostics().toString());
  }

  private String roundTrip(String type, String companion, String json) throws Exception {
    try (var loader = (URLClassLoader) naming.loader()) {
      Class<?> t = loader.loadClass(type);
      Class<?> c = loader.loadClass(companion);
      Object o = c.getMethod("fromJSON", String.class).invoke(null, json);
      return (String) c.getMethod("toJSON", t).invoke(null, o);
    }
  }

  @Test
  public void snakeCaseKeys() throws Exception {
    String json = "{\"user_name\":\"a\",\"pack_size\":3,\"http_status\":\"ok\"}";
    assertEquals(roundTrip("demo.SnakeUser", "demo.internal.SnakeUserJSON", json), json);
  }

  @Test
  public void kebabCaseKeys() throws Exception {
    String json = "{\"user-name\":\"a\"}";
    assertEquals(roundTrip("demo.KebabUser", "demo.internal.KebabUserJSON", json), json);
  }

  @Test
  public void pascalCaseKeys() throws Exception {
    String json = "{\"UserName\":\"a\"}";
    assertEquals(roundTrip("demo.PascalUser", "demo.internal.PascalUserJSON", json), json);
  }

  @Test
  public void camelCaseAcronymKey() throws Exception {
    String json = "{\"userId\":\"a\"}";
    assertEquals(roundTrip("demo.CamelUser", "demo.internal.CamelUserJSON", json), json);
  }

  @Test
  public void identityUnchanged() throws Exception {
    String json = "{\"userName\":\"a\"}";
    assertEquals(roundTrip("demo.IdentityUser", "demo.internal.IdentityUserJSON", json), json);
  }

  @Test
  public void renameOverridesStrategyAndEmptyNameFallsBack() throws Exception {
    String json = "{\"user_name\":\"a\",\"X-Request-ID\":\"b\",\"fall_back\":\"c\"}";
    assertEquals(roundTrip("demo.Renamed", "demo.internal.RenamedJSON", json), json);
  }

  @Test
  public void nestedTypeUsesItsOwnStrategy() throws Exception {
    String json = "{\"outer_name\":\"o\",\"inner_thing\":{\"inner-field\":\"i\"}}";
    assertEquals(roundTrip("demo.Outer", "demo.internal.OuterJSON", json), json);
  }

  @Test
  public void duplicateWireKeyRejected() throws Exception {
    var r = ProcessorHarness.compile("badnaming");
    assertFalse(r.success(), "duplicate wire key must fail compilation");
    assertTrue(r.diagnostics().stream().anyMatch(d ->
            d.contains("duplicate JSON key") && d.contains("[id]")),
        "expected a duplicate-key error for [id], got: " + r.diagnostics());
  }
}
