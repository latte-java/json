/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserScalarsTest {

  /** Hand-written observer that records every callback in order for inspection. */
  static final class RecorderObserver implements JSONObserver<Map<String, Object>> {
    final Map<String, Object> map = new LinkedHashMap<>();
    final List<String> callLog = new ArrayList<>();

    @Override public JSONArrayObserver<?> beginArray(String key) { throw new AssertionError("no array expected"); }
    @Override public JSONObserver<?> beginObject(String key)     { throw new AssertionError("no nested object expected"); }
    @Override public void bigInteger(String key, BigInteger value) { callLog.add("bigInteger:" + key); map.put(key, value); }
    @Override public void bool(String key, boolean value)          { callLog.add("bool:" + key); map.put(key, value); }
    @Override public void decimal(String key, BigDecimal value)    { callLog.add("decimal:" + key); map.put(key, value); }
    @Override public Map<String, Object> finish()                  { callLog.add("finish"); return map; }
    @Override public void integer(String key, long value)          { callLog.add("integer:" + key); map.put(key, value); }
    @Override public void nullValue(String key)                    { callLog.add("nullValue:" + key); map.put(key, null); }
    @Override public void object(String key, Object value)         { callLog.add("object:" + key); map.put(key, value); }
    @Override public void string(String key, String value)         { callLog.add("string:" + key); map.put(key, value); }
    @Override public void array(String key, Object value)          { callLog.add("array:" + key); map.put(key, value); }
  }

  static <T> T parse(String json, JSONObserver<T> obs) {
    return new JSONParser().parse(json, obs);
  }

  @Test
  public void emptyObject() {
    var r = new RecorderObserver();
    parse("{}", r);
    assertTrue(r.map.isEmpty());
    assertEquals(r.callLog, List.of("finish"));
  }

  @Test
  public void singleStringMember() {
    var r = new RecorderObserver();
    parse("{\"name\":\"Alice\"}", r);
    assertEquals(r.map.get("name"), "Alice");
  }

  @Test
  public void twoMembersDifferentTypes() {
    var r = new RecorderObserver();
    parse("{\"name\":\"Alice\",\"age\":30}", r);
    assertEquals(r.map.get("name"), "Alice");
    assertEquals(r.map.get("age"), 30L);
  }

  @Test
  public void integerLongFastPath() {
    var r = new RecorderObserver();
    parse("{\"a\":0,\"b\":-1,\"c\":9223372036854775807}", r);
    assertEquals(r.map.get("a"), 0L);
    assertEquals(r.map.get("b"), -1L);
    assertEquals(r.map.get("c"), 9223372036854775807L);
  }

  @Test
  public void integerOverNineteenDigitsBecomesBigInteger() {
    var r = new RecorderObserver();
    parse("{\"big\":99999999999999999999}", r);
    assertEquals(r.map.get("big"), new BigInteger("99999999999999999999"));
  }

  @Test
  public void numberWithDecimalBecomesBigDecimal() {
    var r = new RecorderObserver();
    parse("{\"d\":12.5}", r);
    assertEquals(r.map.get("d"), new BigDecimal("12.5"));
  }

  @Test
  public void numberWithExponentBecomesBigDecimal() {
    var r = new RecorderObserver();
    parse("{\"d\":1e3}", r);
    assertEquals(r.map.get("d"), new BigDecimal("1e3"));
  }

  @Test
  public void booleansAndNull() {
    var r = new RecorderObserver();
    parse("{\"t\":true,\"f\":false,\"n\":null}", r);
    assertEquals(r.map.get("t"), Boolean.TRUE);
    assertEquals(r.map.get("f"), Boolean.FALSE);
    assertTrue(r.map.containsKey("n"));
    assertNull(r.map.get("n"));
  }

  @Test
  public void stringEscapesParsed() {
    var r = new RecorderObserver();
    parse("{\"s\":\"a\\\"b\\\\c\\nd\\t\"}", r);
    assertEquals(r.map.get("s"), "a\"b\\c\nd\t");
  }

  @Test
  public void unicodeEscape() {
    var r = new RecorderObserver();
    parse("{\"s\":\"\\u0041\"}", r);
    assertEquals(r.map.get("s"), "A");
  }

  @Test
  public void surrogatePair() {
    var r = new RecorderObserver();
    parse("{\"s\":\"\\uD83D\\uDE00\"}", r);
    assertEquals(r.map.get("s"), "😀");
  }

  @Test
  public void whitespaceTolerated() {
    var r = new RecorderObserver();
    parse("  {  \"a\"  :  1  ,  \"b\"  :  2  }  ", r);
    assertEquals(r.map.get("a"), 1L);
    assertEquals(r.map.get("b"), 2L);
  }

  @Test
  public void parseFromBytesUtf8() {
    var r = new RecorderObserver();
    byte[] bytes = "{\"s\":\"héllo\"}".getBytes(StandardCharsets.UTF_8);
    new JSONParser().parse(bytes, r);
    assertEquals(r.map.get("s"), "héllo");
  }
}
