/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONBuilderTest {
  @Test
  public void emptyObjectBuilds() {
    assertEquals(new JSONBuilder().build(), "{}");
  }

  @Test
  public void singleStringMember() {
    assertEquals(new JSONBuilder().string("name", "Alice").build(),
                 "{\"name\":\"Alice\"}");
  }

  @Test
  public void twoStringMembers() {
    assertEquals(
        new JSONBuilder().string("a", "1").string("b", "2").build(),
        "{\"a\":\"1\",\"b\":\"2\"}");
  }

  @Test
  public void integerLongShortByte() {
    String json = new JSONBuilder()
        .integer("i", 42L)
        .integer("zero", 0L)
        .integer("neg", -100L)
        .build();
    assertEquals(json, "{\"i\":42,\"zero\":0,\"neg\":-100}");
  }

  @Test
  public void bigIntegerAndDecimal() {
    String json = new JSONBuilder()
        .bigInteger("b", new BigInteger("99999999999999999999"))
        .decimal("d", new BigDecimal("12.5"))
        .build();
    assertEquals(json, "{\"b\":99999999999999999999,\"d\":12.5}");
  }

  @Test
  public void booleanAndNull() {
    String json = new JSONBuilder(false)
        .bool("active", true)
        .bool("inactive", false)
        .nullValue("none")
        .build();
    assertEquals(json, "{\"active\":true,\"inactive\":false,\"none\":null}");
  }

  @Test
  public void omitNullsByDefault() {
    String json = new JSONBuilder()
        .string("present", "x")
        .nullValue("absent")
        .build();
    assertEquals(json, "{\"present\":\"x\"}");
  }

  @Test
  public void stringEscapes() {
    String json = new JSONBuilder()
        .string("quote", "\"")
        .string("backslash", "\\")
        .string("newline", "\n")
        .string("tab", "\t")
        .string("control", "")
        .build();
    assertEquals(json,
        "{\"quote\":\"\\\"\",\"backslash\":\"\\\\\",\"newline\":\"\\n\",\"tab\":\"\\t\",\"control\":\"\\u0001\"}");
  }

  @Test
  public void unicodeAboveBmpEmittedAsUtf8Bytes() {
    String json = new JSONBuilder().string("emoji", "😀").build();
    int idx = json.indexOf("emoji") + "emoji\":\"".length();
    String emojiPart = json.substring(idx, idx + 2);
    assertEquals(emojiPart, "😀");
  }

  @Test
  public void rawObjectMemberEmbedsJsonString() {
    String addressJson = "{\"city\":\"Boulder\"}";
    String json = new JSONBuilder()
        .string("name", "Alice")
        .object("address", addressJson)
        .build();
    assertEquals(json, "{\"name\":\"Alice\",\"address\":{\"city\":\"Boulder\"}}");
  }

  @Test
  public void rawArrayMemberEmbedsJsonString() {
    String arrJson = "[1,2,3]";
    String json = new JSONBuilder().array("tags", arrJson).build();
    assertEquals(json, "{\"tags\":[1,2,3]}");
  }

  @Test
  public void omitEmptyCollectionsRepresentedAsNullRawJSON() {
    String json = new JSONBuilder()
        .string("a", "x")
        .array("empty", null)
        .object("missing", null)
        .build();
    assertEquals(json, "{\"a\":\"x\"}");
  }

  @Test
  public void buildBytesProducesUtf8() {
    byte[] bytes = new JSONBuilder().string("a", "x").buildBytes();
    assertEquals(new String(bytes, StandardCharsets.UTF_8), "{\"a\":\"x\"}");
  }

  @Test
  public void boxedNullsOmittedByDefault() {
    assertEquals(
        new JSONBuilder().integer("a", (Integer) null).string("b", "x").build(),
        "{\"b\":\"x\"}");
    assertEquals(
        new JSONBuilder().bool("a", (Boolean) null).string("b", "x").build(),
        "{\"b\":\"x\"}");
    assertEquals(
        new JSONBuilder().decimal("c", (Float) null).string("b", "x").build(),
        "{\"b\":\"x\"}");
    assertEquals(
        new JSONBuilder().decimal("d", (Double) null).string("b", "x").build(),
        "{\"b\":\"x\"}");
  }

  @Test
  public void boxedNullsEmittedWhenOmitNullsFalse() {
    assertEquals(new JSONBuilder(false).integer("a", (Integer) null).build(), "{\"a\":null}");
    assertEquals(new JSONBuilder(false).bool("a", (Boolean) null).build(), "{\"a\":null}");
    assertEquals(new JSONBuilder(false).decimal("c", (Float) null).build(), "{\"c\":null}");
    assertEquals(new JSONBuilder(false).decimal("d", (Double) null).build(), "{\"d\":null}");
  }

  @Test
  public void boxedNonNullValuesSerializeCorrectly() {
    assertEquals(new JSONBuilder().integer("a", Integer.valueOf(7)).build(), "{\"a\":7}");
    assertEquals(new JSONBuilder().bool("b", Boolean.TRUE).build(), "{\"b\":true}");
    assertEquals(new JSONBuilder().decimal("c", Float.valueOf(5.5f)).build(), "{\"c\":5.5}");
    assertEquals(new JSONBuilder().decimal("d", Double.valueOf(6.25)).build(), "{\"d\":6.25}");
  }
}
