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
  private int len;
  private final int maxNestingDepth;
  private final ArrayDeque<String> path = new ArrayDeque<>();
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

  public <T> T parsePolymorphic(byte[] bytes, JSONPolymorphicObserver<T> target) {
    if (bytes == null) {
      throw new JSONProcessingException("Input bytes are null");
    }
    return parsePolymorphic(new String(bytes, StandardCharsets.UTF_8), target);
  }

  public <T> T parsePolymorphic(String json, JSONPolymorphicObserver<T> target) {
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
    @SuppressWarnings("unchecked")
    T result = (T) parsePolymorphicObject(target, 0);
    skipWhitespace();
    if (pos != len) {
      throw error("Trailing content after JSON value");
    }
    return result;
  }

  private <T> void dispatchArrayNumber(JSONArrayObserver<T> target) {
    Number n = parseNumber();
    if (n instanceof Long l) target.integer(l);
    else if (n instanceof BigInteger bi) target.bigInteger(bi);
    else target.decimal((BigDecimal) n);
  }

  private <T> void dispatchNumber(JSONObserver<T> target, String key) {
    Number n = parseNumber();
    if (n instanceof Long l) target.integer(key, l);
    else if (n instanceof BigInteger bi) target.bigInteger(key, bi);
    else target.decimal(key, (BigDecimal) n);
  }

  private JSONProcessingException error(String message) {
    String p = path.isEmpty() ? "$" : pathString();
    return new JSONProcessingException(
        message + " at path [" + p + "] position [" + pos + "]");
  }

  private JSONProcessingException error(String message, Throwable cause) {
    String p = path.isEmpty() ? "$" : pathString();
    return new JSONProcessingException(
        message + " at path [" + p + "] position [" + pos + "]", cause);
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

  private int keyEndOfString(int p) {
    if (src.charAt(p) != '"') throw error("Scan expected [\"]");
    int q = p + 1;
    while (q < len) {
      char c = src.charAt(q++);
      if (c == '"') return q;
      if (c == '\\') {
        if (q >= len) throw error("Unterminated escape in scan-ahead");
        q++;
      }
    }
    throw error("Unterminated string in scan-ahead");
  }

  private <T> void parseArrayInto(JSONArrayObserver<T> target, int depth) {
    if (depth > maxNestingDepth) {
      throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
    }
    expect('[');
    skipWhitespace();
    if (pos < len && src.charAt(pos) == ']') {
      pos++;
      return;
    }
    int index = 0;
    while (true) {
      parseArrayValue(target, index, depth);
      skipWhitespace();
      if (pos >= len) throw error("Unterminated array");
      char nc = src.charAt(pos);
      if (nc == ',') { pos++; index++; continue; }
      if (nc == ']') { pos++; return; }
      throw error("Expected [,] or []] but found [" + nc + "]");
    }
  }

  private <T> void parseArrayValue(JSONArrayObserver<T> target, int index, int depth) {
    skipWhitespace();
    if (pos >= len) throw error("Unexpected end of input");

    path.push("[" + index + "]");
    try {
      char c = src.charAt(pos);
      switch (c) {
        case '"' -> target.string(parseString());
        case 't' -> { parseLiteral("true"); target.bool(true); }
        case 'f' -> { parseLiteral("false"); target.bool(false); }
        case 'n' -> { parseLiteral("null"); target.nullValue(); }
        case '-' -> dispatchArrayNumber(target);
        case '{' -> {
          switch (target.beginObject()) {
            case JSONPolymorphicObserver<?> poly ->
                target.object(parsePolymorphicObject(poly, depth + 1));
            case JSONObserver<?> obs -> {
              @SuppressWarnings("unchecked")
              JSONObserver<Object> child = (JSONObserver<Object>) obs;
              parseObjectInto(child, depth + 1);
              target.object(child.finish());
            }
          }
        }
        case '[' -> {
          @SuppressWarnings("unchecked")
          JSONArrayObserver<Object> child = (JSONArrayObserver<Object>) target.beginArray();
          parseArrayInto(child, depth + 1);
          target.array(child.finish());
        }
        default -> {
          if (c >= '0' && c <= '9') dispatchArrayNumber(target);
          else throw error("Unexpected character [" + c + "]");
        }
      }
    } finally {
      path.pop();
    }
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
      throw error("Invalid literal");
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
    if (c == '0') {
      pos++; digitCount++;
      if (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
        throw error("Leading zeros are not allowed in numbers");
      }
    } else if (c >= '1' && c <= '9') {
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
      throw error("Invalid number [" + src.substring(start, pos) + "]", e);
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

  private <T> void parseObjectIntoSkippingKey(JSONObserver<T> target, String skip, int depth) {
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
      if (pos >= len || src.charAt(pos) != '"') throw error("Expected string key");
      String key = parseString();
      skipWhitespace();
      expect(':');
      if (key.equals(skip)) {
        skipWhitespace();
        pos = skipValueAt(pos);
      } else {
        parseValue(target, key, depth);
      }
      skipWhitespace();
      if (pos >= len) throw error("Unterminated object");
      char nc = src.charAt(pos);
      if (nc == ',') { pos++; continue; }
      if (nc == '}') { pos++; return; }
      throw error("Expected [,] or [}] but found [" + nc + "]");
    }
  }

  private Object parsePolymorphicObject(JSONPolymorphicObserver<?> poly, int depth) {
    if (depth > maxNestingDepth) {
      throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
    }
    if (src.charAt(pos) != '{') {
      throw error("Expected [{] for polymorphic object");
    }
    int saved = pos;
    String discriminatorKey = poly.discriminatorKey();
    String discriminatorValue = scanForDiscriminator(discriminatorKey);
    if (discriminatorValue == null) {
      throw error("Discriminator key [" + discriminatorKey + "] missing");
    }
    pos = saved;

    @SuppressWarnings("unchecked")
    JSONObserver<Object> child = (JSONObserver<Object>) poly.observerFor(discriminatorValue);

    parseObjectIntoSkippingKey(child, discriminatorKey, depth);
    return child.finish();
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
          else if (c == '{') {
            switch (target.beginObject(key)) {
              case JSONPolymorphicObserver<?> poly ->
                  target.object(key, parsePolymorphicObject(poly, depth + 1));
              case JSONObserver<?> obs -> {
                @SuppressWarnings("unchecked")
                JSONObserver<Object> child = (JSONObserver<Object>) obs;
                parseObjectInto(child, depth + 1);
                target.object(key, child.finish());
              }
            }
          }
          else if (c == '[') {
            @SuppressWarnings("unchecked")
            JSONArrayObserver<Object> child = (JSONArrayObserver<Object>) target.beginArray(key);
            parseArrayInto(child, depth + 1);
            Object value = child.finish();
            target.array(key, value);
          }
          else {
            throw error("Unexpected character [" + c + "]");
          }
        }
      }
    } finally {
      path.pop();
    }
  }

  private String pathString() {
    var sb = new StringBuilder("$");
    var it = path.descendingIterator();
    while (it.hasNext()) {
      String segment = it.next();
      if (segment.startsWith("[")) {
        sb.append(segment);
      } else {
        sb.append('.').append(segment);
      }
    }
    return sb.toString();
  }

  private char peek() {
    return src.charAt(pos);
  }

  private String scanForDiscriminator(String discriminatorKey) {
    int p = pos;
    if (src.charAt(p) != '{') throw error("Scan-ahead expected [{]");
    p++;
    int braceDepth = 1;
    int bracketDepth = 0;

    while (p < len && braceDepth > 0) {
      char c = src.charAt(p);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { p++; continue; }

      if (braceDepth == 1 && bracketDepth == 0 && c == '"') {
        int keyStart = p;
        String key = scanString(p);
        p = keyEndOfString(keyStart);
        while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                        || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
        if (p >= len || src.charAt(p) != ':') throw error("Scan-ahead expected [:] after key");
        p++;
        while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                        || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
        if (key.equals(discriminatorKey)) {
          if (p >= len || src.charAt(p) != '"') {
            throw error("Discriminator value for [" + discriminatorKey + "] must be a string");
          }
          return scanString(p);
        }
        p = skipValueAt(p);
        while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                        || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
        if (p < len && src.charAt(p) == ',') p++;
        continue;
      }

      if (c == '{')      braceDepth++;
      else if (c == '}') braceDepth--;
      else if (c == '[') bracketDepth++;
      else if (c == ']') bracketDepth--;
      else if (c == '"') {
        p = keyEndOfString(p);
        continue;
      }
      p++;
    }
    return null;
  }

  private String scanString(int p) {
    if (src.charAt(p) != '"') throw error("Scan expected [\"]");
    int q = p + 1;
    StringBuilder sb = new StringBuilder();
    while (q < len) {
      char c = src.charAt(q++);
      if (c == '"') return sb.toString();
      if (c == '\\') {
        if (q >= len) throw error("Scan-ahead unterminated escape");
        char esc = src.charAt(q++);
        switch (esc) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (q + 4 > len) throw error("Scan-ahead truncated \\u escape");
            int code;
            try {
              code = Integer.parseInt(src, q, q + 4, 16);
            } catch (NumberFormatException e) {
              throw error("Scan-ahead invalid \\u escape [\\u" + src.substring(q, q + 4) + "]", e);
            }
            q += 4;
            sb.append((char) code);
          }
          default -> throw error("Scan-ahead invalid escape [\\" + esc + "]");
        }
      } else {
        sb.append(c);
      }
    }
    throw error("Scan-ahead unterminated string");
  }

  private int skipContainerAt(int p, char open, char close) {
    int depth = 0;
    while (p < len) {
      char c = src.charAt(p);
      if (c == '"') { p = keyEndOfString(p); continue; }
      if (c == open) depth++;
      else if (c == close) {
        depth--;
        if (depth == 0) return p + 1;
      }
      p++;
    }
    throw error("Unterminated container in scan-ahead");
  }

  private int skipNumberAt(int p) {
    if (src.charAt(p) == '-') p++;
    while (p < len) {
      char c = src.charAt(p);
      if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') p++;
      else break;
    }
    return p;
  }

  private int skipValueAt(int p) {
    while (p < len && (src.charAt(p) == ' ' || src.charAt(p) == '\t'
                    || src.charAt(p) == '\n' || src.charAt(p) == '\r')) p++;
    if (p >= len) throw error("Unexpected end during scan-ahead");
    char c = src.charAt(p);
    return switch (c) {
      case '"' -> keyEndOfString(p);
      case '{' -> skipContainerAt(p, '{', '}');
      case '[' -> skipContainerAt(p, '[', ']');
      case 't' -> p + 4;
      case 'f' -> p + 5;
      case 'n' -> p + 4;
      default -> skipNumberAt(p);
    };
  }

  private void skipWhitespace() {
    while (pos < len) {
      char c = src.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
      else break;
    }
  }
}
