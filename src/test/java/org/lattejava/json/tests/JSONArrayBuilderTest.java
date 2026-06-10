/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONArrayBuilderTest {
  @Test
  public void emptyArray() {
    assertEquals(new JSONArrayBuilder().build(), "[]");
  }

  @Test
  public void scalars() {
    String json = new JSONArrayBuilder()
        .string("a").string("b").build();
    assertEquals(json, "[\"a\",\"b\"]");
  }

  @Test
  public void mixedKinds() {
    String json = new JSONArrayBuilder()
        .integer(1L).bool(true).nullValue()
        .bigInteger(new BigInteger("99999999999999999999"))
        .decimal(new BigDecimal("1.5"))
        .build();
    assertEquals(json, "[1,true,null,99999999999999999999,1.5]");
  }

  @Test
  public void stringEscaping() {
    assertEquals(new JSONArrayBuilder().string("a\"b\n").build(),
        "[\"a\\\"b\\n\"]");
  }

  @Test
  public void rawEmbedsValidJson() {
    assertEquals(new JSONArrayBuilder().raw("{\"k\":1}").string("x").build(),
        "[{\"k\":1},\"x\"]");
  }

  @Test
  public void nullElementsArePreserved() {
    assertEquals(new JSONArrayBuilder().nullValue().string(null).integer(2L).build(),
        "[null,null,2]");
  }

  @Test
  public void boxedBooleanNullIsPreserved() {
    assertEquals(new JSONArrayBuilder().bool((Boolean) null).build(), "[null]");
  }

  @Test
  public void boxedBooleanNonNullEmitsValue() {
    assertEquals(new JSONArrayBuilder().bool(Boolean.TRUE).bool(Boolean.FALSE).build(),
        "[true,false]");
  }

  @Test
  public void boxedLongNonNullEmitsValue() {
    assertEquals(new JSONArrayBuilder().integer(Long.valueOf(7L)).build(), "[7]");
  }

  @Test
  public void boxedLongNullIsPreserved() {
    assertEquals(new JSONArrayBuilder().integer((Long) null).build(), "[null]");
  }

  @Test
  public void buildBytesUtf8() {
    byte[] b = new JSONArrayBuilder().string("x").buildBytes();
    assertEquals(new String(b, StandardCharsets.UTF_8), "[\"x\"]");
  }

  @Test
  public void anyWritesNaturalShapesAndNests() {
    String json = new JSONArrayBuilder()
        .any("hi")
        .any(42L)
        .any(null)
        .any(java.util.List.of(1L, 2L))
        .build();
    assertEquals(json, "[\"hi\",42,null,[1,2]]");
  }
}
