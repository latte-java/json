/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Observer-driven JSON parser. Walks a {@link String} cursor and emits typed callbacks on a target
 * {@link JSONObserver}. Maintains a JSON-path stack for diagnostic context on thrown
 * {@link JSONProcessingException}s. Top-level JSON value must be an object; arrays, strings, numbers,
 * booleans, and {@code null} at the top level are rejected (the library targets OpenAPI DTOs and JWT
 * payloads, both of which guarantee object envelopes).
 *
 * @author Brian Pontarelli
 */
public final class JSONParser {
  private final int maxNestingDepth;

  private final ArrayDeque<String> path = new ArrayDeque<>();
  private int len;
  private int pos;
  private String src;

  public JSONParser() {
    this(64);
  }

  public JSONParser(int maxNestingDepth) {
    if (maxNestingDepth <= 0) {
      throw new IllegalArgumentException(
          "maxNestingDepth must be > 0 but found [" + maxNestingDepth + "]");
    }
    this.maxNestingDepth = maxNestingDepth;
  }

  public <T> T parse(byte[] bytes, JSONObserver<T> target) {
    if (bytes == null) {
      throw new JSONProcessingException("Input bytes are null");
    }
    return parse(new String(bytes, StandardCharsets.UTF_8), target);
  }

  public <T> T parse(String json, JSONObserver<T> target) {
    if (json == null) {
      throw new JSONProcessingException("Input string is null");
    }
    if (target == null) {
      throw new JSONProcessingException("Observer is null");
    }
    this.src = json;
    this.len = json.length();
    this.pos = 0;
    this.path.clear();

    skipWhitespace();
    if (pos >= len) {
      throw error("Empty input");
    }
    if (peek() != '{') {
      throw error("Expected top-level JSON object but found [" + peek() + "]");
    }
    parseObjectInto(target, 0);
    skipWhitespace();
    if (pos != len) {
      throw error("Trailing content after JSON value");
    }
    return target.finish();
  }

  private JSONProcessingException error(String message) {
    String p = path.isEmpty() ? "$" : pathString();
    return new JSONProcessingException(
        message + " at path [" + p + "] position [" + pos + "]");
  }

  private void expect(char c) {
    if (pos >= len) {
      throw error("Expected [" + c + "] but reached end of input");
    }
    if (src.charAt(pos) != c) {
      throw error("Expected [" + c + "] but found [" + src.charAt(pos) + "]");
    }
    pos++;
  }

  private int parseHex4() {
    if (pos + 4 > len) {
      throw error("Truncated \\u escape");
    }
    int code = 0;
    for (int i = 0; i < 4; i++) {
      char c = src.charAt(pos++);
      int d;
      if (c >= '0' && c <= '9')      d = c - '0';
      else if (c >= 'a' && c <= 'f') d = 10 + (c - 'a');
      else if (c >= 'A' && c <= 'F') d = 10 + (c - 'A');
      else throw error("Invalid hex digit [" + c + "] in \\u escape");
      code = (code << 4) | d;
    }
    return code;
  }

  private void parseLiteral(String literal) {
    if (pos + literal.length() > len
        || !src.regionMatches(pos, literal, 0, literal.length())) {
      throw error("Invalid literal at position [" + pos + "]");
    }
    pos += literal.length();
  }

  private Number parseNumber() {
    int start = pos;
    int digitCount = 0;
    boolean hasDecimal = false;
    boolean hasExponent = false;

    if (src.charAt(pos) == '-') {
      pos++;
      if (pos >= len) throw error("Number ends after [-]");
    }
    char c = src.charAt(pos);
    if (c == '0') { pos++; digitCount++; }
    else if (c >= '1' && c <= '9') {
      while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        pos++; digitCount++;
      }
    } else {
      throw error("Invalid number");
    }
    if (pos < len && src.charAt(pos) == '.') {
      hasDecimal = true;
      pos++;
      int fracStart = pos;
      while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        pos++; digitCount++;
      }
      if (pos == fracStart) throw error("Number has [.] with no fractional digits");
    }
    if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
      hasExponent = true;
      pos++;
      if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
      int expStart = pos;
      while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        pos++; digitCount++;
      }
      if (pos == expStart) throw error("Number has exponent marker with no exponent digits");
    }

    try {
      if (hasDecimal || hasExponent) {
        return new BigDecimal(src.substring(start, pos));
      }
      if (digitCount <= 18) {
        return Long.parseLong(src, start, pos, 10);
      }
      return new BigInteger(src.substring(start, pos));
    } catch (NumberFormatException e) {
      throw new JSONProcessingException(
          "Invalid number [" + src.substring(start, pos) + "] at path ["
              + (path.isEmpty() ? "$" : pathString()) + "]", e);
    }
  }

  private <T> void parseObjectInto(JSONObserver<T> target, int depth) {
    if (depth > maxNestingDepth) {
      throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
    }
    expect('{');
    skipWhitespace();
    if (pos < len && src.charAt(pos) == '}') {
      pos++;
      return;
    }
    while (true) {
      skipWhitespace();
      if (pos >= len || src.charAt(pos) != '"') {
        throw error("Expected string key");
      }
      String key = parseString();
      skipWhitespace();
      expect(':');
      parseValue(target, key, depth);
      skipWhitespace();
      if (pos >= len) throw error("Unterminated object");
      char nc = src.charAt(pos);
      if (nc == ',') { pos++; continue; }
      if (nc == '}') { pos++; return; }
      throw error("Expected [,] or [}] but found [" + nc + "]");
    }
  }

  private String parseString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (pos < len) {
      char c = src.charAt(pos++);
      if (c == '"') return sb.toString();
      if (c == '\\') {
        if (pos >= len) throw error("Unterminated escape sequence");
        char esc = src.charAt(pos++);
        switch (esc) {
          case '"'  -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/'  -> sb.append('/');
          case 'b'  -> sb.append('\b');
          case 'f'  -> sb.append('\f');
          case 'n'  -> sb.append('\n');
          case 'r'  -> sb.append('\r');
          case 't'  -> sb.append('\t');
          case 'u'  -> {
            int code = parseHex4();
            if (Character.isHighSurrogate((char) code)) {
              if (pos + 1 >= len || src.charAt(pos) != '\\' || src.charAt(pos + 1) != 'u') {
                throw error("Lone high surrogate [\\u" + Integer.toHexString(code) + "]");
              }
              pos += 2;
              int low = parseHex4();
              if (!Character.isLowSurrogate((char) low)) {
                throw error("High surrogate not followed by low surrogate");
              }
              sb.append((char) code).append((char) low);
            } else if (Character.isLowSurrogate((char) code)) {
              throw error("Lone low surrogate [\\u" + Integer.toHexString(code) + "]");
            } else {
              sb.append((char) code);
            }
          }
          default -> throw error("Invalid escape [\\" + esc + "]");
        }
      } else if (c < 0x20) {
        throw error("Unescaped control character [U+" + String.format("%04X", (int) c) + "] in string");
      } else {
        sb.append(c);
      }
    }
    throw error("Unterminated string");
  }

  private <T> void parseValue(JSONObserver<T> target, String key, int depth) {
    skipWhitespace();
    if (pos >= len) throw error("Unexpected end of input");

    path.push(key);
    try {
      char c = src.charAt(pos);
      switch (c) {
        case '"' -> target.string(key, parseString());
        case 't' -> { parseLiteral("true"); target.bool(key, true); }
        case 'f' -> { parseLiteral("false"); target.bool(key, false); }
        case 'n' -> { parseLiteral("null"); target.nullValue(key); }
        case '-' -> dispatchNumber(target, key);
        default -> {
          if (c >= '0' && c <= '9') dispatchNumber(target, key);
          else if (c == '{' || c == '[') {
            // Containers handled in a later task — TASK 13 introduces nested object/array dispatch.
            throw error("Container values not yet implemented in this task");
          } else {
            throw error("Unexpected character [" + c + "]");
          }
        }
      }
    } finally {
      path.pop();
    }
  }

  private <T> void dispatchNumber(JSONObserver<T> target, String key) {
    Number n = parseNumber();
    if (n instanceof Long l) target.integer(key, l);
    else if (n instanceof BigInteger bi) target.bigInteger(key, bi);
    else target.decimal(key, (BigDecimal) n);
  }

  private String pathString() {
    var sb = new StringBuilder("$");
    var it = path.descendingIterator();
    while (it.hasNext()) {
      sb.append('.').append(it.next());
    }
    return sb.toString();
  }

  private char peek() {
    return src.charAt(pos);
  }

  private void skipWhitespace() {
    while (pos < len) {
      char c = src.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
      else break;
    }
  }
}
