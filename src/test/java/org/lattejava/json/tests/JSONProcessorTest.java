/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Tests for {@link JSONProcessor}.
 *
 * @author Daniel DeGroff
 */
public class JSONProcessorTest {

  /**
   * Builds a JSON number token whose digit-run length (digits only, sign chars excluded) is exactly len.
   */
  private static String buildNumberToken(int len, String form) {
    switch (form) {
      case "integer": {
        // len digits, leading "1" then zeros to avoid leading-zero ambiguity
        StringBuilder sb = new StringBuilder(len);
        sb.append('1');
        sb.repeat("0", Math.max(0, len - 1));
        return sb.toString();
      }
      case "decimal": {
        // integer part 1 digit + "." + (len-1) decimal digits = len digits total
        if (len < 2) return "1." + repeat(Math.max(0, len - 1));
        StringBuilder sb = new StringBuilder(len + 1);
        sb.append('1').append('.');
        sb.repeat("0", len - 1);
        return sb.toString();
      }
      case "exponent": {
        // (len-1) integer digits + "e" + 1 exponent digit = len digit chars
        StringBuilder sb = new StringBuilder(len + 1);
        sb.append('1');
        sb.repeat("0", Math.max(0, len - 2));
        sb.append('e').append('5');
        return sb.toString();
      }
      default:
        throw new IllegalArgumentException("unknown form: " + form);
    }
  }

  private static String repeat(int n) {
    StringBuilder sb = new StringBuilder(n);
    sb.repeat(String.valueOf('0'), Math.max(0, n));
    return sb.toString();
  }

  @Test
  public void arrayDepthCounts() {
    // Use case: depth boundary with arrays counts toward the same nesting limit
    JSONProcessor jp = new JSONProcessor();
    // Build {"a":[[[...1...]]]} -- 1 object + N arrays = N+1 nesting levels
    int arrays = 16; // total depth = 17 -> rejected
    StringBuilder sb = new StringBuilder("{\"a\":");
    sb.repeat("[", arrays);
    sb.append("1");
    sb.repeat("]", arrays);
    sb.append("}");
    expectThrows(JSONProcessingException.class,
        () -> jp.deserialize(sb.toString().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void arrayElementsBoundaryRespected() {
    // Use case: an array with exactly maxArrayElements entries is accepted; one more is rejected.
    JSONProcessor accept = new JSONProcessor(16, 1000, 1000, 5, false);
    String ok = "{\"a\":[1,2,3,4,5]}";
    try {
      accept.deserialize(ok.getBytes(StandardCharsets.UTF_8));
    } catch (JSONProcessingException e) {
      fail("Expected 5 elements to be accepted; threw: " + e.getMessage());
    }
    String over = "{\"a\":[1,2,3,4,5,6]}";
    expectThrows(JSONProcessingException.class,
        () -> accept.deserialize(over.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void bigDecimalHighPrecision() {
    JSONProcessor jp = new JSONProcessor();
    BigDecimal precise = new BigDecimal("3.141592653589793238462643383279502884197169399375");
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("pi", precise);
    Map<String, Object> result = jp.deserialize(jp.serialize(input));
    assertEquals(result.get("pi"), precise);
    assertTrue(result.get("pi") instanceof BigDecimal);
  }

  @Test
  public void bigIntegerBeyondLong() {
    // Use case: Large numbers (BigInteger beyond Long.MAX_VALUE, BigDecimal high precision)
    JSONProcessor jp = new JSONProcessor();
    BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("n", huge);
    Map<String, Object> result = jp.deserialize(jp.serialize(input));
    assertEquals(result.get("n"), huge);
    assertTrue(result.get("n") instanceof BigInteger);
  }

  @Test
  public void constructorRejectsNonPositiveArrayElements() {
    // Use case: zero/negative caps would silently disable the wide-array defense
    expectThrows(IllegalArgumentException.class, () -> new JSONProcessor(16, 1000, 1000, 0, false));
    expectThrows(IllegalArgumentException.class, () -> new JSONProcessor(16, 1000, 1000, -1, false));
  }

  @Test
  public void constructorRejectsNonPositiveDepth() {
    // Use case: Constructor validates parameters
    expectThrows(IllegalArgumentException.class,
        () -> new JSONProcessor(0, 1000, false));
  }

  @Test
  public void constructorRejectsNonPositiveNumberLength() {
    expectThrows(IllegalArgumentException.class,
        () -> new JSONProcessor(16, 0, false));
  }

  @Test
  public void constructorRejectsNonPositiveObjectMembers() {
    // Use case: zero/negative caps would silently disable the wide-object defense
    expectThrows(IllegalArgumentException.class, () -> new JSONProcessor(16, 1000, 0, 10000, false));
    expectThrows(IllegalArgumentException.class, () -> new JSONProcessor(16, 1000, -1, 10000, false));
  }

  @Test
  public void defaultConstructorUsesSpecDefaults() {
    // Use case: Default constructor uses the documented defaults (16 / 1000 / false)
    JSONProcessor jp = new JSONProcessor();
    // default rejects duplicates
    expectThrows(JSONProcessingException.class,
        () -> jp.deserialize("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
  }

  @DataProvider(name = "depthBoundary")
  public Object[][] depthBoundary() {
    // Use case: depth=16 accepted, depth=17 rejected -- DataProvider on the boundary
    return new Object[][]{
        {16, true},   // accepted
        {17, false},  // rejected
        {1, true},    // baseline
        {15, true},   // just under
    };
  }

  @Test(dataProvider = "depthBoundary")
  public void depthBoundaryRespected(int depth, boolean accepted) {
    JSONProcessor jp = new JSONProcessor();
    // Build {"a":{"a":{"a":...{"a":1}}}} where the outermost object is depth 1.
    StringBuilder sb = new StringBuilder();
    sb.repeat("{\"a\":", Math.max(0, depth));
    sb.append("1");
    sb.repeat("}", Math.max(0, depth));
    String json = sb.toString();
    if (accepted) {
      try {
        Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(result);
      } catch (JSONProcessingException e) {
        fail("Expected depth=" + depth + " to be accepted; threw: " + e.getMessage());
      }
    } else {
      expectThrows(JSONProcessingException.class,
          () -> jp.deserialize(json.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  public void deserializedObjectIsLinkedHashMap() {
    // Use case: deserialized objects use LinkedHashMap (preserves insertion order)
    JSONProcessor jp = new JSONProcessor();
    String json = "{\"z\":1,\"a\":2,\"m\":3}";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    assertTrue(result instanceof LinkedHashMap);
    List<String> keys = new ArrayList<>(result.keySet());
    assertEquals(keys, Arrays.asList("z", "a", "m"));
  }

  @Test
  public void duplicateKeyDoesNotConsumeObjectMembersBudget() {
    // Use case: when allowDuplicateJSONKeys=true, repeating an existing key updates the existing
    // entry rather than counting against maxObjectMembers (LinkedHashMap.put semantics).
    JSONProcessor jp = new JSONProcessor(16, 1000, 2, 10000, true);
    String json = "{\"a\":1,\"a\":2,\"b\":3}";
    try {
      Map<String, Object> r = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
      assertEquals(r.size(), 2);
      assertEquals(r.get("a"), 2L);
      assertEquals(r.get("b"), 3L);
    } catch (JSONProcessingException e) {
      fail("Expected duplicate-key updates to not count against the cap; threw: " + e.getMessage());
    }
  }

  @Test
  public void duplicateKeysAcceptedWhenAllowed() {
    // Use case: Duplicate JSON key accepted when allowDuplicateJSONKeys=true
    JSONProcessor jp = new JSONProcessor(16, 1000, true);
    String json = "{\"a\":1,\"a\":2}";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    // last-wins is the conventional behavior; we just require successful parse
    assertEquals(result.get("a"), 2L);
  }

  @Test
  public void duplicateKeysRejectedByDefault() {
    // Use case: Duplicate JSON key in payload rejected by default
    JSONProcessor jp = new JSONProcessor();
    String json = "{\"a\":1,\"a\":2}";
    expectThrows(JSONProcessingException.class,
        () -> jp.deserialize(json.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void emptyNestedArray() {
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("arr", new ArrayList<>());
    byte[] bytes = jp.serialize(input);
    Map<String, Object> result = jp.deserialize(bytes);
    assertEquals(result.get("arr"), new ArrayList<>());
  }

  @Test
  public void emptyNestedObject() {
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("obj", new LinkedHashMap<>());
    byte[] bytes = jp.serialize(input);
    Map<String, Object> result = jp.deserialize(bytes);
    assertEquals(result.get("obj"), new LinkedHashMap<>());
  }

  @Test
  public void emptyObject() {
    // Use case: Empty objects and arrays
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> empty = new LinkedHashMap<>();
    byte[] bytes = jp.serialize(empty);
    assertEquals(new String(bytes, StandardCharsets.UTF_8), "{}");
    assertEquals(jp.deserialize(bytes), empty);
  }

  @Test
  public void integerWithExponentParsesAsDecimal() {
    // Use case: Integer with positive exponent without decimal point parses as decimal
    JSONProcessor jp = new JSONProcessor();
    String json = "{\"n\":1e3}";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    assertTrue(result.get("n") instanceof BigDecimal,
        "expected BigDecimal, got " + result.get("n").getClass());
  }

  @DataProvider(name = "jsonTypes")
  public Object[][] jsonTypes() {
    // Use case: Round-trip serialization of every JSON type (string, integer, decimal,
    // boolean true/false, null, nested object, nested array)
    Map<String, Object> nestedObj = new LinkedHashMap<>();
    nestedObj.put("inner", "value");

    return new Object[][]{
        {"stringClaim", "hello world"},
        {"integerClaim", 42L},
        {"decimalClaim", new BigDecimal("3.14159")},
        {"trueClaim", Boolean.TRUE},
        {"falseClaim", Boolean.FALSE},
        {"nullClaim", null},
        {"objectClaim", nestedObj},
        {"arrayClaim", Arrays.asList("a", "b", "c")},
    };
  }

  @DataProvider(name = "malformedJson")
  public Object[][] malformedJson() {
    // Use case: Malformed JSON input -- DataProvider over variants
    return new Object[][]{
        {"unterminatedString", "{\"a\":\"foo"},
        {"unterminatedObject", "{\"a\":1"},
        {"unterminatedArray", "{\"a\":[1,2"},
        {"trailingCommaObject", "{\"a\":1,}"},
        {"trailingCommaArray", "{\"a\":[1,2,]}"},
        {"invalidEscape", "{\"a\":\"\\q\"}"},
        {"truncated", "{"},
        {"truncatedKey", "{\"a"},
        {"missingColon", "{\"a\" 1}"},
        {"missingValue", "{\"a\":}"},
        {"unquotedKey", "{a:1}"},
        {"singleQuoteKey", "{'a':1}"},
        {"badLiteral", "{\"a\":tru}"},
        {"empty", ""},
        {"justWhitespace", "   "},
        {"trailingGarbage", "{}garbage"},
        {"badUnicodeEscape", "{\"a\":\"\\uXYZ1\"}"},
        {"shortUnicodeEscape", "{\"a\":\"\\u12\"}"},
        {"loneHighSurrogate", "{\"a\":\"\\uD83D\"}"},
        {"loneLowSurrogate", "{\"a\":\"\\uDE00\"}"},
        {"badNumberMultipleDots", "{\"a\":1.2.3}"},
        {"badNumberLeadingPlus", "{\"a\":+1}"},
        {"controlInString", "{\"a\":\"\u0001\"}"}, // raw control char in string per RFC 8259
    };
  }

  @Test(dataProvider = "malformedJson")
  public void malformedJsonRejected(String label, String json) {
    JSONProcessor jp = new JSONProcessor();
    try {
      Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
      fail("Expected JSONProcessingException for " + label + "; got: " + result);
    } catch (JSONProcessingException expected) {
      // pass
    }
  }

  @Test
  public void negativeNumbers() {
    // Use case: Negative numbers parse correctly
    JSONProcessor jp = new JSONProcessor();
    String json = "{\"i\":-42,\"d\":-3.14}";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(result.get("i"), -42L);
    assertEquals(result.get("d"), new BigDecimal("-3.14"));
  }

  @Test
  public void nestedStructures() {
    // Use case: Nested structures (objects within arrays within objects)
    JSONProcessor jp = new JSONProcessor();

    Map<String, Object> innermost = new LinkedHashMap<>();
    innermost.put("deep", "value");
    innermost.put("number", 7L);

    List<Object> middle = new ArrayList<>();
    middle.add(innermost);
    middle.add("string");
    middle.add(1L);

    Map<String, Object> outer = new LinkedHashMap<>();
    outer.put("list", middle);
    outer.put("flag", Boolean.TRUE);

    byte[] bytes = jp.serialize(outer);
    Map<String, Object> result = jp.deserialize(bytes);

    assertEquals(result, outer);
  }

  @DataProvider(name = "numberLengthBoundary")
  public Object[][] numberLengthBoundary() {
    // Use case: number digit-run boundary: 1000 accepted / 1001 rejected
    // DataProvider over (length, form, position) to cover integer / decimal / integer-with-exponent
    // in both header and payload positions (here the "position" determines where in the JSON the
    // number lives, but functionally it's the same parser path -- both header & payload pass
    // through deserialize()).
    return new Object[][]{
        // {label, lengthOfDigitRun, form, position, expectedAccepted}
        {"int-1000-payload", 1000, "integer", "payload", true},
        {"int-1001-payload", 1001, "integer", "payload", false},
        {"int-1000-header", 1000, "integer", "header", true},
        {"int-1001-header", 1001, "integer", "header", false},
        {"dec-1000-payload", 1000, "decimal", "payload", true},
        {"dec-1001-payload", 1001, "decimal", "payload", false},
        {"dec-1000-header", 1000, "decimal", "header", true},
        {"dec-1001-header", 1001, "decimal", "header", false},
        {"exp-1000-payload", 1000, "exponent", "payload", true},
        {"exp-1001-payload", 1001, "exponent", "payload", false},
        {"exp-1000-header", 1000, "exponent", "header", true},
        {"exp-1001-header", 1001, "exponent", "header", false},
    };
  }

  @Test(dataProvider = "numberLengthBoundary")
  public void numberLengthBoundaryRespected(String label, int len, String form, String position,
                                            boolean expectedAccepted) {
    JSONProcessor jp = new JSONProcessor();
    String numberToken = buildNumberToken(len, form);
    // The "header" / "payload" distinction is symbolic: the parser is the same.
    // We still build different shapes to ensure both code paths exercise the limit.
    String json;
    if ("header".equals(position)) {
      json = "{\"alg\":\"none\",\"x\":" + numberToken + "}";
    } else {
      json = "{\"data\":{\"value\":" + numberToken + "}}";
    }
    if (expectedAccepted) {
      try {
        Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(result, "label=" + label);
      } catch (JSONProcessingException e) {
        fail("Expected " + label + " to be accepted; threw: " + e.getMessage());
      }
    } else {
      expectThrows(JSONProcessingException.class,
          () -> jp.deserialize(json.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  public void numberLengthExcludesSignChars() {
    // Use case: Number digit-run measured includes integer + decimal + exponent digits
    // (sign chars excluded). Verify a number with negative sign and negative exponent stays
    // within limits for a 1000-digit total.
    JSONProcessor jp = new JSONProcessor();
    // 1 + 998 integer digits + 'e' + '-' + 1 exponent digit = 1000 digit chars
    StringBuilder digits = new StringBuilder().append('1');
    digits.repeat("0", 998);
    String token = "-" + digits + "e-5";
    String json = "{\"n\":" + token + "}";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    assertTrue(result.containsKey("n"));
  }

  @Test
  public void objectMembersBoundaryRespected() {
    // Use case: an object with exactly maxObjectMembers entries is accepted; one more is rejected.
    JSONProcessor accept = new JSONProcessor(16, 1000, 5, 10000, false);
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < 5; i++) {
      if (i > 0) sb.append(',');
      sb.append("\"k").append(i).append("\":").append(i);
    }
    sb.append('}');
    try {
      accept.deserialize(sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (JSONProcessingException e) {
      fail("Expected 5 members to be accepted; threw: " + e.getMessage());
    }

    StringBuilder over = new StringBuilder("{");
    for (int i = 0; i < 6; i++) {
      if (i > 0) over.append(',');
      over.append("\"k").append(i).append("\":").append(i);
    }
    over.append('}');
    expectThrows(JSONProcessingException.class,
        () -> accept.deserialize(over.toString().getBytes(StandardCharsets.UTF_8)));
  }

  @Test(dataProvider = "jsonTypes")
  public void roundTripJsonType(String key, Object value) {
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put(key, value);

    byte[] bytes = jp.serialize(input);
    Map<String, Object> result = jp.deserialize(bytes);

    assertEquals(result.get(key), value);
    assertTrue(result.containsKey(key));
  }

  @Test
  public void serializeAllSupportedTypes() {
    // Use case: serialize() handles String, Number (Integer/Long/BigInteger/Double/BigDecimal),
    // Boolean, null, Map, List
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("str", "hi");
    input.put("integer", 7);
    input.put("longVal", 9_000_000_000L);
    input.put("biginteger", new BigInteger("12345678901234567890"));
    input.put("doubleVal", 3.5);
    input.put("bigdecimal", new BigDecimal("1.5"));
    input.put("boolTrue", Boolean.TRUE);
    input.put("nullVal", null);
    input.put("mapVal", new LinkedHashMap<>(Map.of("k", "v")));
    input.put("listVal", List.of("a", "b"));

    byte[] bytes = jp.serialize(input);
    Map<String, Object> result = jp.deserialize(bytes);

    assertEquals(((Number) result.get("integer")).intValue(), 7);
    assertEquals(((Number) result.get("longVal")).longValue(), 9_000_000_000L);
    assertEquals(result.get("biginteger"), new BigInteger("12345678901234567890"));
    assertEquals(((Number) result.get("doubleVal")).doubleValue(), 3.5, 0.0);
    assertEquals(result.get("boolTrue"), Boolean.TRUE);
    assertNull(result.get("nullVal"));
    assertTrue(result.containsKey("nullVal"));
    assertEquals(((Map<?, ?>) result.get("mapVal")).get("k"), "v");
    assertEquals(result.get("listVal"), List.of("a", "b"));
  }

  @Test
  public void serializeRejectsUnsupportedType() {
    // Use case: serialize() rejects unsupported value types
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("k", new Object());
    expectThrows(JSONProcessingException.class, () -> jp.serialize(input));
  }

  @DataProvider(name = "topLevelNonObject")
  public Object[][] topLevelNonObject() {
    // Use case: Top-level non-object input throws JSONProcessingException (per JSONProcessor javadoc)
    return new Object[][]{
        {"[1,2,3]"},
        {"\"hello\""},
        {"42"},
        {"true"},
        {"false"},
        {"null"},
    };
  }

  @Test(dataProvider = "topLevelNonObject")
  public void topLevelNonObjectRejected(String json) {
    JSONProcessor jp = new JSONProcessor();
    expectThrows(JSONProcessingException.class,
        () -> jp.deserialize(json.getBytes(StandardCharsets.UTF_8)));
  }

  @Test(dataProvider = "unicodeStrings")
  public void unicodeStringRoundTrip(String label, String value) {
    JSONProcessor jp = new JSONProcessor();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("k", value);

    byte[] bytes = jp.serialize(input);
    Map<String, Object> result = jp.deserialize(bytes);

    assertEquals(result.get("k"), value, "label=" + label);
  }

  @DataProvider(name = "unicodeStrings")
  public Object[][] unicodeStrings() {
    // Use case: Unicode string escaping (multi-byte characters, control characters,
    // surrogate pairs, named escapes)
    return new Object[][]{
        {"ascii", "plain text"},
        {"backslash", "back\\slash"},
        {"quote", "with\"quote"},
        {"newline", "line1\nline2"},
        {"tab", "col1\tcol2"},
        {"cr", "a\rb"},
        {"backspace", "a\bb"},
        {"formfeed", "a\fb"},
        {"controlChar", "x\u0001y"},
        {"controlChar1F", "x\u001Fy"},
        {"unicodeBMP", "caf\u00e9"},
        {"japanese", "\u65e5\u672c\u8a9e"},
        {"emojiSurrogatePair", "\uD83D\uDE00"},
        {"slash", "http://example.com/path"},
    };
  }

  @Test
  public void whitespaceBetweenTokensAccepted() throws Exception {
    // Use case: Whitespace between tokens is permitted (RFC 8259 §2)
    JSONProcessor jp = new JSONProcessor();
    String json = "  {  \"a\"  :  1  ,  \"b\"  :  [  1  ,  2  ]  }  ";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(result.get("a"), 1L);
    assertEquals(result.get("b"), Arrays.asList(1L, 2L));
  }

  @Test
  public void zero() throws Exception {
    // Use case: Zero parses correctly (single-digit integer is a special case)
    JSONProcessor jp = new JSONProcessor();
    String json = "{\"n\":0}";
    Map<String, Object> result = jp.deserialize(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(result.get("n"), 0L);
  }
}
