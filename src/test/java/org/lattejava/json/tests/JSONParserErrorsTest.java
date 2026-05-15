/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserErrorsTest {

  static JSONProcessingException parseAndCatch(String json) {
    try {
      new JSONParser().parse(json, new AnyObjectObserver());
      throw new AssertionError("Expected JSONProcessingException for input [" + json + "]");
    } catch (JSONProcessingException e) {
      return e;
    }
  }

  @Test
  public void topLevelArrayRejected() {
    var e = parseAndCatch("[1,2,3]");
    assertTrue(e.getMessage().contains("top-level JSON object"));
  }

  @Test
  public void topLevelStringRejected() {
    var e = parseAndCatch("\"hi\"");
    assertTrue(e.getMessage().contains("top-level JSON object"));
  }

  @Test
  public void emptyInputRejected() {
    var e = parseAndCatch("");
    assertTrue(e.getMessage().contains("Empty input"));
  }

  @Test
  public void trailingContentRejected() {
    var e = parseAndCatch("{}garbage");
    assertTrue(e.getMessage().contains("Trailing content"));
  }

  @Test
  public void pathRecordedForNestedScalar() {
    var e = parseAndCatch("{\"a\":{\"b\":@}}");
    assertTrue(e.getMessage().contains("[$.a.b]"),
        "Expected path [$.a.b] in message but was: " + e.getMessage());
  }

  @Test
  public void pathRecordedForArrayIndex() {
    var e = parseAndCatch("{\"xs\":[1,@]}");
    assertTrue(e.getMessage().contains("[$.xs[1]]"),
        "Expected path [$.xs[1]] in message but was: " + e.getMessage());
  }

  @Test
  public void unterminatedStringRejected() {
    var e = parseAndCatch("{\"s\":\"hello");
    assertTrue(e.getMessage().contains("Unterminated string"));
  }

  @Test
  public void invalidEscapeRejected() {
    var e = parseAndCatch("{\"s\":\"\\x\"}");
    assertTrue(e.getMessage().contains("Invalid escape"));
  }

  @Test
  public void truncatedUnicodeEscapeRejected() {
    var e = parseAndCatch("{\"s\":\"\\u00\"}");
    assertTrue(e.getMessage().contains("Truncated") || e.getMessage().contains("Invalid"));
  }

  @Test
  public void loneHighSurrogateRejected() {
    var e = parseAndCatch("{\"s\":\"\\uD83D\"}");
    assertTrue(e.getMessage().contains("Lone high surrogate"));
  }

  @Test
  public void numberDotWithoutFractionRejected() {
    var e = parseAndCatch("{\"d\":1.}");
    assertTrue(e.getMessage().contains("fractional"));
  }

  @Test
  public void numberExponentWithoutDigitsRejected() {
    var e = parseAndCatch("{\"d\":1e}");
    assertTrue(e.getMessage().contains("exponent"));
  }

  @Test
  public void unterminatedObjectRejected() {
    var e = parseAndCatch("{\"a\":1");
    assertTrue(e.getMessage().contains("Unterminated") || e.getMessage().contains("Expected"));
  }

  @Test
  public void unterminatedArrayRejected() {
    var e = parseAndCatch("{\"xs\":[1,2");
    assertTrue(e.getMessage().contains("Unterminated") || e.getMessage().contains("Expected"));
  }

  @Test
  public void nullInputRejected() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parse((String) null, new AnyObjectObserver()));
    assertTrue(e.getMessage().contains("Input string is null"));
  }

  @Test
  public void nullBytesRejected() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parse((byte[]) null, new AnyObjectObserver()));
    assertTrue(e.getMessage().contains("Input bytes are null"));
  }

  @Test
  public void nullObserverRejected() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parse("{}", null));
    assertTrue(e.getMessage().contains("Observer is null"));
  }

  @Test
  public void maxNestingDepthEnforced() {
    StringBuilder open = new StringBuilder();
    StringBuilder close = new StringBuilder();
    for (int i = 0; i < 70; i++) {
      open.append("{\"x\":");
      close.append("}");
    }
    open.append("1");
    var e = parseAndCatch(open.append(close).toString());
    assertTrue(e.getMessage().contains("Maximum nesting depth"));
  }
}
