/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserRawTest {
  /** Collects the decoded raw span of every JSON object the parser reports, in completion order. */
  static final class Recorder implements JSONObserver<String> {
    private final List<String> spans;

    Recorder(List<String> spans) {
      this.spans = spans;
    }

    @Override public JSONArrayObserver<?> beginArray(String key) { return new ArrayRecorder(spans); }
    @Override public JSONObjectHandler beginObject(String key) { return new Recorder(spans); }
    @Override public void bigInteger(String key, BigInteger value) { }
    @Override public void bool(String key, boolean value) { }
    @Override public void decimal(String key, BigDecimal value) { }
    @Override public String finish() { return null; }
    @Override public void integer(String key, long value) { }
    @Override public void nullValue(String key) { }
    @Override public void object(String key, Object value) { }
    @Override public void raw(byte[] src, int start, int end) {
      spans.add(new String(src, start, end - start, StandardCharsets.UTF_8));
    }
    @Override public void string(String key, String value) { }
    @Override public void array(String key, Object value) { }
  }

  static final class ArrayRecorder implements JSONArrayObserver<String> {
    private final List<String> spans;

    ArrayRecorder(List<String> spans) {
      this.spans = spans;
    }

    @Override public JSONArrayObserver<?> beginArray() { return new ArrayRecorder(spans); }
    @Override public JSONObjectHandler beginObject() { return new Recorder(spans); }
    @Override public void bigInteger(BigInteger value) { }
    @Override public void bool(boolean value) { }
    @Override public void decimal(BigDecimal value) { }
    @Override public String finish() { return null; }
    @Override public void integer(long value) { }
    @Override public void nullValue() { }
    @Override public void object(Object value) { }
    @Override public void string(String value) { }
    @Override public void array(Object value) { }
  }

  private static List<String> spansOf(String json) {
    List<String> spans = new ArrayList<>();
    new JSONParser().parse(json, new Recorder(spans));
    return spans;
  }

  @Test
  public void flatObjectReportsWholeInput() {
    assertEquals(spansOf("{\"a\":1}"), List.of("{\"a\":1}"));
  }

  @Test
  public void interiorWhitespaceAndKeyOrderPreserved() {
    assertEquals(spansOf("{ \"b\" : 2,  \"a\" : 1 }"), List.of("{ \"b\" : 2,  \"a\" : 1 }"));
  }

  @Test
  public void surroundingWhitespaceExcluded() {
    assertEquals(spansOf("  \n{\"a\":1}\t "), List.of("{\"a\":1}"));
  }

  @Test
  public void emptyObjectReportsBraces() {
    assertEquals(spansOf("{}"), List.of("{}"));
  }

  @Test
  public void nestedObjectsReportedInnerFirst() {
    assertEquals(spansOf("{\"x\":{\"y\":1}}"), List.of("{\"y\":1}", "{\"x\":{\"y\":1}}"));
  }

  @Test
  public void emptyNestedObjectReported() {
    assertEquals(spansOf("{\"x\":{}}"), List.of("{}", "{\"x\":{}}"));
  }

  @Test
  public void arrayElementObjectsEachReported() {
    assertEquals(spansOf("{\"a\":[{\"i\":1},{\"i\":2}]}"),
        List.of("{\"i\":1}", "{\"i\":2}", "{\"a\":[{\"i\":1},{\"i\":2}]}"));
  }

  @Test
  public void spanIsByteAccurateAcrossMultiByteCharacters() {
    // The span is byte offsets; a 3-byte character before the nested object must not shift it.
    assertEquals(spansOf("{\"k\":\"é€漢\",\"x\":{\"y\":1}}"),
        List.of("{\"y\":1}", "{\"k\":\"é€漢\",\"x\":{\"y\":1}}"));
  }

  @Test
  public void spanIsByteAccurateAcrossEscapedBraces() {
    // Braces inside a string must not be mistaken for the object's own delimiters.
    assertEquals(spansOf("{\"k\":\"a{b}c\\\"d\",\"x\":{\"y\":1}}"),
        List.of("{\"y\":1}", "{\"k\":\"a{b}c\\\"d\",\"x\":{\"y\":1}}"));
  }

  @Test
  public void byteOverloadReportsSameSpan() {
    List<String> spans = new ArrayList<>();
    new JSONParser().parse("{\"a\":1}".getBytes(StandardCharsets.UTF_8), new Recorder(spans));
    assertEquals(spans, List.of("{\"a\":1}"));
  }

  @Test
  public void rawNotDeliveredWhenObjectParseFails() {
    // The hook fires at an object's closing '}'; a value that fails partway through must never reach it.
    List<String> spans = new ArrayList<>();
    expectThrows(JSONProcessingException.class, () -> new JSONParser().parse("{\"a\": bogus}", new Recorder(spans)));
    assertTrue(spans.isEmpty(), "raw must not be delivered for an object whose parse failed");
  }
}
