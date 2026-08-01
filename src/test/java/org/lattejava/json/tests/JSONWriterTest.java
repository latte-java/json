/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONWriterTest {
  @Test
  public void anyMemberNaturalShapes() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.any("s", "x");
    w.any("b", true);
    w.any("l", 42L);
    w.any("bi", new BigInteger("99999999999999999999"));
    w.any("bd", new BigDecimal("1.50"));
    w.any("d", 0.25d);
    w.any("map", new LinkedHashMap<>(Map.of("inner", 1L)));
    w.any("list", List.of("a", 2L));
    w.endObject();
    assertEquals(w.finishString(),
        "{\"s\":\"x\",\"b\":true,\"l\":42,\"bi\":99999999999999999999,\"bd\":1.50,\"d\":0.25,\"map\":{\"inner\":1},\"list\":[\"a\",2]}");
  }

  @Test
  public void anyMemberNullOmittedWhenOmitNulls() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.any("gone", null);
    w.string("kept", "v");
    w.endObject();
    assertEquals(w.finishString(), "{\"kept\":\"v\"}");
  }

  @Test
  public void anyMemberRejectsUnsupportedType() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    try {
      w.any("bad", new Object());
      fail("Expected JSONProcessingException");
    } catch (JSONProcessingException expected) {
      assertTrue(expected.getMessage().contains("Unsupported catch-all value type"));
    } finally {
      w.finishBytes();
    }
  }

  @Test
  public void arrayElementNullsAlwaysEmitted() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.key("a");
    w.beginArray();
    w.stringElement("x");
    w.stringElement(null);
    w.integerElement((Long) null);
    w.nullElement();
    w.endArray();
    w.endObject();
    assertEquals(w.finishString(), "{\"a\":[\"x\",null,null,null]}");
  }

  @Test
  public void controlCharactersEscapeAsLowercaseHex() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.string("c", "\u0001\u001F");
    w.endObject();
    assertEquals(w.finishString(), "{\"c\":\"\\u0001\\u001f\"}");
  }

  @Test
  public void deeplyNestedFirstTrackingPastSixtyFourLevels() {
    JSONWriter w = JSONWriter.acquire(true);
    int levels = 70;
    w.beginObject();
    for (int i = 0; i < levels; i++) {
      w.key("n");
      w.beginObject();
    }
    w.string("leaf", "v");
    for (int i = 0; i < levels; i++) {
      w.endObject();
    }
    w.endObject();
    String json = w.finishString();
    assertTrue(json.startsWith("{\"n\":{\"n\":"));
    assertTrue(json.endsWith("{\"leaf\":\"v\"}" + "}".repeat(levels)));
  }

  @Test
  public void emptyContainers() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.endObject();
    assertEquals(w.finishString(), "{}");

    w = JSONWriter.acquire(true);
    w.beginObject();
    w.key("a");
    w.beginArray();
    w.endArray();
    w.key("o");
    w.beginObject();
    w.endObject();
    w.endObject();
    assertEquals(w.finishString(), "{\"a\":[],\"o\":{}}");
  }

  @Test
  public void escapingNamedAndQuoteBackslash() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.string("s", "a\"b\\c\bd\fe\nf\rg\th");
    w.endObject();
    assertEquals(w.finishString(), "{\"s\":\"a\\\"b\\\\c\\bd\\fe\\nf\\rg\\th\"}");
  }

  @Test
  public void finishBytesMatchesFinishString() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.string("s", "héllo 🚀");
    w.endObject();
    String s = w.finishString();

    JSONWriter w2 = JSONWriter.acquire(true);
    w2.beginObject();
    w2.string("s", "héllo 🚀");
    w2.endObject();
    byte[] bytes = w2.finishBytes();
    assertEquals(new String(bytes, StandardCharsets.UTF_8), s);
  }

  @Test
  public void integerEdgeCases() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.integer("min", Long.MIN_VALUE);
    w.integer("max", Long.MAX_VALUE);
    w.integer("zero", 0L);
    w.integer("neg", -7L);
    w.endObject();
    assertEquals(w.finishString(),
        "{\"min\":-9223372036854775808,\"max\":9223372036854775807,\"zero\":0,\"neg\":-7}");
  }

  @Test
  public void keyedNullsFollowOmitNulls() {
    JSONWriter omitting = JSONWriter.acquire(true);
    omitting.beginObject();
    omitting.string("gone", null);
    omitting.nullValue("alsoGone");
    omitting.bool("kept", true);
    omitting.endObject();
    assertEquals(omitting.finishString(), "{\"kept\":true}");

    JSONWriter keeping = JSONWriter.acquire(false);
    keeping.beginObject();
    keeping.string("a", null);
    keeping.nullValue("b");
    keeping.endObject();
    assertEquals(keeping.finishString(), "{\"a\":null,\"b\":null}");
  }

  @Test
  public void largeOutputGrowsAndSubsequentWriterStillWorks() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.key("a");
    w.beginArray();
    for (int i = 0; i < 50_000; i++) {
      w.integerElement(i);
    }
    w.endArray();
    w.endObject();
    String big = w.finishString();
    assertTrue(big.length() > 250_000);
    assertTrue(big.startsWith("{\"a\":[0,1,2,"));
    assertTrue(big.endsWith(",49999]}"));

    JSONWriter w2 = JSONWriter.acquire(true);
    w2.beginObject();
    w2.string("s", "after");
    w2.endObject();
    assertEquals(w2.finishString(), "{\"s\":\"after\"}");
  }

  @Test
  public void nestedSharedWriter() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.string("name", "outer");
    w.key("inner");
    w.beginObject();
    w.integer("i", 1L);
    w.endObject();
    w.key("list");
    w.beginArray();
    w.beginObject();
    w.integer("i", 2L);
    w.endObject();
    w.beginObject();
    w.integer("i", 3L);
    w.endObject();
    w.endArray();
    w.string("after", "z");
    w.endObject();
    assertEquals(w.finishString(),
        "{\"name\":\"outer\",\"inner\":{\"i\":1},\"list\":[{\"i\":2},{\"i\":3}],\"after\":\"z\"}");
  }

  @Test
  public void numbersAndBooleansAndDecimals() {
    JSONWriter w = JSONWriter.acquire(false);
    w.beginObject();
    w.bigInteger("b", new BigInteger("99999999999999999999"));
    w.decimal("d", new BigDecimal("12.5"));
    w.decimal("dd", 0.1d);
    w.decimal("f", 2.5f);
    w.integer("boxed", (Number) Integer.valueOf(9));
    w.bool("t", true);
    w.bool("boxedFalse", Boolean.FALSE);
    w.endObject();
    assertEquals(w.finishString(),
        "{\"b\":99999999999999999999,\"d\":12.5,\"dd\":0.1,\"f\":2.5,\"boxed\":9,\"t\":true,\"boxedFalse\":false}");
  }

  @Test
  public void prettyDeepNestingPastSixtyFourLevels() {
    JSONWriter w = JSONWriter.acquire(true, true);
    int levels = 70;
    w.beginObject();
    for (int i = 0; i < levels; i++) {
      w.key("n");
      w.beginObject();
    }
    w.string("leaf", "v");
    for (int i = 0; i < levels; i++) {
      w.endObject();
    }
    w.endObject();
    String json = w.finishString();
    assertTrue(json.contains("\n" + " ".repeat(2 * (levels + 1)) + "\"leaf\": \"v\"\n"), json);
    assertTrue(json.endsWith("\n  }\n}"), json);
  }

  @Test
  public void prettyEmptyContainersStayInline() {
    JSONWriter w = JSONWriter.acquire(true, true);
    w.beginObject();
    w.endObject();
    assertEquals(w.finishString(), "{}");

    w = JSONWriter.acquire(true, true);
    w.beginObject();
    w.key("a");
    w.beginArray();
    w.endArray();
    w.key("o");
    w.beginObject();
    w.endObject();
    w.endObject();
    assertEquals(w.finishString(), """
        {
          "a": [],
          "o": {}
        }""");
  }

  @Test
  public void prettyIndentsAnyMapsAndLists() {
    JSONWriter w = JSONWriter.acquire(true, true);
    w.beginObject();
    w.any("map", new LinkedHashMap<>(Map.of("inner", 1L)));
    w.any("list", List.of("a", 2L));
    w.endObject();
    assertEquals(w.finishString(), """
        {
          "map": {
            "inner": 1
          },
          "list": [
            "a",
            2
          ]
        }""");
  }

  @Test
  public void prettyIndentsNestedObjectsAndArraysWithTwoSpaces() {
    JSONWriter w = JSONWriter.acquire(true, true);
    w.beginObject();
    w.string("name", "outer");
    w.key("inner");
    w.beginObject();
    w.integer("i", 1L);
    w.endObject();
    w.key("list");
    w.beginArray();
    w.stringElement("x");
    w.nullElement();
    w.beginObject();
    w.integer("i", 2L);
    w.endObject();
    w.endArray();
    w.string("after", "z");
    w.endObject();
    assertEquals(w.finishString(), """
        {
          "name": "outer",
          "inner": {
            "i": 1
          },
          "list": [
            "x",
            null,
            {
              "i": 2
            }
          ],
          "after": "z"
        }""");
  }

  @Test
  public void prettyOmittedNullsLeaveNoDanglingSeparators() {
    JSONWriter w = JSONWriter.acquire(true, true);
    w.beginObject();
    w.string("gone", null);
    w.string("kept", "v");
    w.nullValue("alsoGone");
    w.endObject();
    assertEquals(w.finishString(), """
        {
          "kept": "v"
        }""");

    JSONWriter allOmitted = JSONWriter.acquire(true, true);
    allOmitted.beginObject();
    allOmitted.string("gone", null);
    allOmitted.endObject();
    assertEquals(allOmitted.finishString(), "{}");
  }

  @Test
  public void doubleFormattingMatchesBigDecimalPlainString() {
    double[] values = {0.0, -0.0, 1.0, -1.0, 0.1, 0.25, 2.5, 1234.5678, -987.125, 1e-3, 9999999.0,
        1e7, 1e20, 4.9e-324, 1.7976931348623157e308, 0.001003009027081244, 1.0 / 3.0};
    for (double d : values) {
      JSONWriter w = JSONWriter.acquire(true);
      w.beginObject();
      w.decimal("d", d);
      w.endObject();
      assertEquals(w.finishString(), "{\"d\":" + BigDecimal.valueOf(d).toPlainString() + "}",
          "Mismatch for double [" + d + "]");
    }
  }

  @Test
  public void doubleNonFiniteRejectedLikeBigDecimalValueOf() {
    for (double d : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      JSONWriter w = JSONWriter.acquire(true);
      w.beginObject();
      try {
        w.decimal("d", d);
        fail("Expected NumberFormatException for [" + d + "]");
      } catch (NumberFormatException expected) {
        // Same exception BigDecimal.valueOf threw before the fast path existed.
      } finally {
        w.finishBytes();
      }
    }
  }

  @Test
  public void instantMatchesToString() {
    List<Instant> values = new ArrayList<>(List.of(
        Instant.EPOCH,
        Instant.parse("2026-06-12T08:30:00Z"),
        Instant.parse("1969-12-31T23:59:59Z"),
        Instant.parse("1582-10-15T00:00:00Z"),
        Instant.parse("0001-01-01T00:00:00Z"),
        Instant.parse("9999-12-31T23:59:59.999999999Z"),
        Instant.parse("+10000-01-01T00:00:00Z"),
        Instant.parse("-0001-06-15T12:00:00Z"),
        Instant.parse("2024-02-29T00:00:00Z"),
        Instant.MIN,
        Instant.MAX));
    for (int nano : new int[]{0, 1, 100, 120, 123, 1_000, 999_000, 1_000_000, 120_000_000, 999_999_999}) {
      values.add(Instant.EPOCH.plusNanos(nano));
      values.add(Instant.parse("2026-06-12T23:59:59Z").plusNanos(nano));
    }
    for (long epochSecond = -400L * 86400; epochSecond <= 400L * 86400; epochSecond += 86400 * 13 + 12345) {
      values.add(Instant.ofEpochSecond(epochSecond, 7_000_000));
    }
    for (Instant instant : values) {
      JSONWriter w = JSONWriter.acquire(true);
      w.beginObject();
      w.instant("i", instant);
      w.endObject();
      assertEquals(w.finishString(), "{\"i\":\"" + instant + "\"}", "Mismatch for instant [" + instant + "]");
    }
  }

  @Test
  public void uuidMatchesToString() {
    List<UUID> values = List.of(
        new UUID(0L, 0L),
        new UUID(-1L, -1L),
        new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L),
        UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479"),
        new UUID(Long.MIN_VALUE, Long.MAX_VALUE));
    for (UUID uuid : values) {
      JSONWriter w = JSONWriter.acquire(true);
      w.beginObject();
      w.uuid("u", uuid);
      w.endObject();
      assertEquals(w.finishString(), "{\"u\":\"" + uuid + "\"}", "Mismatch for UUID [" + uuid + "]");
    }
  }

  @Test
  public void unicodeTwoThreeFourByteAndUnpairedSurrogate() {
    JSONWriter w = JSONWriter.acquire(true);
    w.beginObject();
    w.string("two", "é");
    w.string("three", "性");
    w.string("four", "🚀");
    w.string("lone", "a\uD800b");
    w.endObject();
    byte[] bytes = w.finishBytes();
    String expected = "{\"two\":\"é\",\"three\":\"性\",\"four\":\"🚀\",\"lone\":\"a?b\"}";
    assertEquals(bytes, expected.getBytes(StandardCharsets.UTF_8));
  }
}
