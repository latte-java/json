/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Observer-driven JSON parser. Walks the input {@code byte[]} directly (the {@link String} overloads
 * UTF-8 encode once and delegate) and emits typed callbacks on a target {@link JSONObserver}. Keys are
 * matched without allocation through {@link JSONObserver#field(byte[], int, int)}; escape-free strings
 * are sliced in a single exact-size allocation; integers accumulate during the digit scan. Top-level JSON
 * value must be an object; arrays, strings, numbers, booleans, and {@code null} at the top level are
 * rejected (the library targets OpenAPI DTOs and JWT payloads, both of which guarantee object envelopes).
 *
 * <p>Diagnostic JSON paths in error messages are reconstructed lazily: nothing is tracked on the hot
 * path, and on failure the input is re-walked up to the failure position to rebuild the {@code $...}
 * path string.
 *
 * <p>Instances are not thread-safe — they carry per-parse cursor state. Create a new
 * {@code JSONParser} per parse call (generated companion code does exactly this).
 *
 * @author Brian Pontarelli
 */
public final class JSONParser {
  private static final ThreadLocal<char[]> CHAR_SCRATCH = ThreadLocal.withInitial(() -> new char[512]);
  private static final VarHandle LONGS =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final int NUMBER_BIG_INTEGER = 1;
  private static final int NUMBER_DECIMAL = 2;
  private static final int NUMBER_LONG = 0;
  private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[512]);
  private int len;
  private final int maxNestingDepth;
  private int numberEnd;
  private long numberLong;
  private int numberStart;
  private int pos;
  private byte[] src;

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

  private static boolean isWhitespace(byte c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  public <T> T parse(byte[] bytes, JSONObserver<T> target) {
    if (bytes == null) {
      throw new JSONProcessingException("Input bytes are null");
    }
    if (target == null) {
      throw new JSONProcessingException("Observer is null");
    }
    this.src = bytes;
    this.len = bytes.length;
    this.pos = 0;

    skipWhitespace();
    if (pos >= len) {
      throw error("Empty input");
    }
    if (src[pos] != '{') {
      throw error("Expected top-level JSON object but found [" + (char) (src[pos] & 0xFF) + "]");
    }
    parseObjectInto(target, 0);
    skipWhitespace();
    if (pos != len) {
      throw error("Trailing content after JSON value");
    }
    return target.finish();
  }

  public <T> T parse(String json, JSONObserver<T> target) {
    if (json == null) {
      throw new JSONProcessingException("Input string is null");
    }
    return parse(json.getBytes(StandardCharsets.UTF_8), target);
  }

  public <T> T parsePolymorphic(byte[] bytes, JSONPolymorphicObserver<T> target) {
    if (bytes == null) {
      throw new JSONProcessingException("Input bytes are null");
    }
    if (target == null) {
      throw new JSONProcessingException("Observer is null");
    }
    this.src = bytes;
    this.len = bytes.length;
    this.pos = 0;

    skipWhitespace();
    if (pos >= len) {
      throw error("Empty input");
    }
    if (src[pos] != '{') {
      throw error("Expected top-level JSON object but found [" + (char) (src[pos] & 0xFF) + "]");
    }
    @SuppressWarnings("unchecked")
    T result = (T) parsePolymorphicObject(target, 0);
    skipWhitespace();
    if (pos != len) {
      throw error("Trailing content after JSON value");
    }
    return result;
  }

  public <T> T parsePolymorphic(String json, JSONPolymorphicObserver<T> target) {
    if (json == null) {
      throw new JSONProcessingException("Input string is null");
    }
    return parsePolymorphic(json.getBytes(StandardCharsets.UTF_8), target);
  }

  /**
   * Decodes a string containing escapes by copying clean byte runs into a reused scratch buffer and
   * resolving escapes inline; one {@code new String(scratch, UTF_8)} at the end gives the JDK's
   * vectorized decode (and its malformed-sequence replacement semantics, matching the previous
   * whole-input String decode).
   */
  private String decodeEscapedString(int start) {
    byte[] scratch = SCRATCH.get();
    int n = 0;
    int p = start;
    while (p < len) {
      int runStart = p;
      int b = 0;
      while (p < len) {
        b = src[p] & 0xFF;
        if (b == '"' || b == '\\' || b < 0x20) {
          break;
        }
        p++;
      }
      if (p > runStart) {
        int count = p - runStart;
        if (n + count > scratch.length) {
          scratch = Arrays.copyOf(scratch, Math.max(scratch.length << 1, n + count));
          SCRATCH.set(scratch);
        }
        System.arraycopy(src, runStart, scratch, n, count);
        n += count;
      }
      if (p >= len) {
        break;
      }
      if (b == '"') {
        pos = p + 1;
        return new String(scratch, 0, n, StandardCharsets.UTF_8);
      }
      if (b != '\\') {
        pos = p;
        throw error("Unescaped control character [U+" + String.format("%04X", b) + "] in string");
      }
      if (p + 1 >= len) {
        pos = p;
        throw error("Unterminated escape sequence");
      }
      if (n + 4 > scratch.length) {
        scratch = Arrays.copyOf(scratch, scratch.length << 1);
        SCRATCH.set(scratch);
      }
      int esc = src[p + 1] & 0xFF;
      p += 2;
      switch (esc) {
        case '"' -> scratch[n++] = '"';
        case '\\' -> scratch[n++] = '\\';
        case '/' -> scratch[n++] = '/';
        case 'b' -> scratch[n++] = '\b';
        case 'f' -> scratch[n++] = '\f';
        case 'n' -> scratch[n++] = '\n';
        case 'r' -> scratch[n++] = '\r';
        case 't' -> scratch[n++] = '\t';
        case 'u' -> {
          pos = p;
          int code = parseHex4();
          p = pos;
          if (Character.isHighSurrogate((char) code)) {
            if (p + 1 >= len || src[p] != '\\' || src[p + 1] != 'u') {
              pos = p;
              throw error("Lone high surrogate [\\u" + Integer.toHexString(code) + "]");
            }
            pos = p + 2;
            int low = parseHex4();
            p = pos;
            if (!Character.isLowSurrogate((char) low)) {
              throw error("High surrogate not followed by low surrogate");
            }
            int cp = Character.toCodePoint((char) code, (char) low);
            if (n + 4 > scratch.length) {
              scratch = Arrays.copyOf(scratch, scratch.length << 1);
              SCRATCH.set(scratch);
            }
            scratch[n++] = (byte) (0xF0 | (cp >> 18));
            scratch[n++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            scratch[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            scratch[n++] = (byte) (0x80 | (cp & 0x3F));
          } else if (Character.isLowSurrogate((char) code)) {
            throw error("Lone low surrogate [\\u" + Integer.toHexString(code) + "]");
          } else if (code < 0x80) {
            scratch[n++] = (byte) code;
          } else if (code < 0x800) {
            scratch[n++] = (byte) (0xC0 | (code >> 6));
            scratch[n++] = (byte) (0x80 | (code & 0x3F));
          } else {
            scratch[n++] = (byte) (0xE0 | (code >> 12));
            scratch[n++] = (byte) (0x80 | ((code >> 6) & 0x3F));
            scratch[n++] = (byte) (0x80 | (code & 0x3F));
          }
        }
        default -> {
          pos = p - 2;
          throw error("Invalid escape [\\" + (char) esc + "]");
        }
      }
    }
    pos = p;
    throw error("Unterminated string");
  }

  private JSONProcessingException error(String message) {
    return new JSONProcessingException(
        message + " at path [" + pathAt(pos) + "] position [" + pos + "]");
  }

  private JSONProcessingException error(String message, Throwable cause) {
    return new JSONProcessingException(
        message + " at path [" + pathAt(pos) + "] position [" + pos + "]", cause);
  }

  private void expect(char c) {
    if (pos >= len) {
      throw error("Expected [" + c + "] but reached end of input");
    }
    if (src[pos] != c) {
      throw error("Expected [" + c + "] but found [" + (char) (src[pos] & 0xFF) + "]");
    }
    pos++;
  }

  private int keyEndOfString(int p) {
    if (src[p] != '"') throw error("Scan expected [\"]");
    int q = p + 1;
    while (q < len) {
      int c = src[q++] & 0xFF;
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
    if (pos < len && src[pos] == ']') {
      pos++;
      return;
    }
    while (true) {
      parseArrayValue(target, depth);
      skipWhitespace();
      if (pos >= len) throw error("Unterminated array");
      byte nc = src[pos];
      if (nc == ',') { pos++; continue; }
      if (nc == ']') { pos++; return; }
      throw error("Expected [,] or []] but found [" + (char) (nc & 0xFF) + "]");
    }
  }

  private <T> void parseArrayValue(JSONArrayObserver<T> target, int depth) {
    skipWhitespace();
    if (pos >= len) throw error("Unexpected end of input");

    byte c = src[pos];
    switch (c) {
      case '"' -> target.string(parseString());
      case 't' -> { parseLiteral("true"); target.bool(true); }
      case 'f' -> { parseLiteral("false"); target.bool(false); }
      case 'n' -> { parseLiteral("null"); target.nullValue(); }
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
        if (c == '-' || (c >= '0' && c <= '9')) {
          switch (parseNumberValue()) {
            case NUMBER_LONG -> target.integer(numberLong);
            case NUMBER_BIG_INTEGER -> target.bigInteger(numberBigInteger());
            default -> target.decimal(numberBigDecimal());
          }
        } else {
          throw error("Unexpected character [" + (char) (c & 0xFF) + "]");
        }
      }
    }
  }

  private int parseHex4() {
    if (pos + 4 > len) {
      throw error("Truncated \\u escape");
    }
    int code = 0;
    for (int i = 0; i < 4; i++) {
      int c = src[pos++] & 0xFF;
      int d;
      if (c >= '0' && c <= '9')      d = c - '0';
      else if (c >= 'a' && c <= 'f') d = 10 + (c - 'a');
      else if (c >= 'A' && c <= 'F') d = 10 + (c - 'A');
      else throw error("Invalid hex digit [" + (char) c + "] in \\u escape");
      code = (code << 4) | d;
    }
    return code;
  }

  private void parseLiteral(String literal) {
    int length = literal.length();
    if (pos + length > len) {
      throw error("Invalid literal");
    }
    for (int i = 0; i < length; i++) {
      if (src[pos + i] != literal.charAt(i)) {
        throw error("Invalid literal");
      }
    }
    pos += length;
  }

  /**
   * Parses a number token, leaving its classification in {@code numberLong} (for {@link #NUMBER_LONG})
   * or its slice bounds for the BigInteger/BigDecimal constructors. Validation and the 18-digit Long
   * cutoff are identical to the previous String-walking implementation.
   */
  private int parseNumberValue() {
    int start = pos;
    int digitCount = 0;
    boolean hasDecimal = false;
    boolean hasExponent = false;
    boolean negative = false;
    long value = 0;

    if (src[pos] == '-') {
      negative = true;
      pos++;
      if (pos >= len) throw error("Number ends after [-]");
    }
    int c = src[pos] & 0xFF;
    if (c == '0') {
      pos++; digitCount++;
      if (pos < len && src[pos] >= '0' && src[pos] <= '9') {
        throw error("Leading zeros are not allowed in numbers");
      }
    } else if (c >= '1' && c <= '9') {
      while (pos < len && src[pos] >= '0' && src[pos] <= '9') {
        if (digitCount < 19) {
          value = value * 10 + (src[pos] - '0');
        }
        pos++; digitCount++;
      }
    } else {
      throw error("Invalid number");
    }
    if (pos < len && src[pos] == '.') {
      hasDecimal = true;
      pos++;
      int fracStart = pos;
      while (pos < len && src[pos] >= '0' && src[pos] <= '9') {
        pos++; digitCount++;
      }
      if (pos == fracStart) throw error("Number has [.] with no fractional digits");
    }
    if (pos < len && (src[pos] == 'e' || src[pos] == 'E')) {
      hasExponent = true;
      pos++;
      if (pos < len && (src[pos] == '+' || src[pos] == '-')) pos++;
      int expStart = pos;
      while (pos < len && src[pos] >= '0' && src[pos] <= '9') {
        pos++; digitCount++;
      }
      if (pos == expStart) throw error("Number has exponent marker with no exponent digits");
    }

    numberStart = start;
    numberEnd = pos;
    if (hasDecimal || hasExponent) {
      return NUMBER_DECIMAL;
    }
    if (digitCount <= 18) {
      numberLong = negative ? -value : value;
      return NUMBER_LONG;
    }
    return NUMBER_BIG_INTEGER;
  }

  private BigDecimal numberBigDecimal() {
    String text = new String(src, numberStart, numberEnd - numberStart, StandardCharsets.ISO_8859_1);
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      throw error("Invalid number [" + text + "]", e);
    }
  }

  private BigInteger numberBigInteger() {
    String text = new String(src, numberStart, numberEnd - numberStart, StandardCharsets.ISO_8859_1);
    try {
      return new BigInteger(text);
    } catch (NumberFormatException e) {
      throw error("Invalid number [" + text + "]", e);
    }
  }

  private <T> void parseObjectInto(JSONObserver<T> target, int depth) {
    parseObjectBody(target, depth, null);
  }

  private <T> void parseObjectBody(JSONObserver<T> target, int depth, byte[] skipKey) {
    if (depth > maxNestingDepth) {
      throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
    }
    int objectStart = pos;
    expect('{');
    skipWhitespace();
    if (pos < len && src[pos] == '}') {
      pos++;
      target.raw(src, objectStart, pos);
      return;
    }
    boolean dispatchUnknown = target.dispatchUnknown();
    while (true) {
      skipWhitespace();
      if (pos >= len || src[pos] != '"') {
        throw error("Expected string key");
      }
      // Scan the raw key. Escape-free keys (the overwhelmingly common case) match without allocating.
      int keyStart = pos + 1;
      int keyEnd = keyStart;
      boolean escaped = false;
      while (keyEnd < len) {
        int b = src[keyEnd] & 0xFF;
        if (b == '"') break;
        if (b == '\\') { escaped = true; break; }
        if (b < 0x20) {
          pos = keyEnd;
          throw error("Unescaped control character [U+" + String.format("%04X", b) + "] in string");
        }
        keyEnd++;
      }
      if (keyEnd >= len) {
        pos = len;
        throw error("Unterminated string");
      }

      int field;
      String key = null;
      if (escaped) {
        pos = keyStart;
        key = decodeEscapedString(keyStart);
        field = target.fieldOf(key);
      } else {
        pos = keyEnd + 1;
        field = target.field(src, keyStart, keyEnd - keyStart);
      }

      skipWhitespace();
      expect(':');

      boolean skipThis = skipKey != null
          && (key == null ? Arrays.equals(src, keyStart, keyEnd, skipKey, 0, skipKey.length)
                          : Arrays.equals(key.getBytes(StandardCharsets.UTF_8), skipKey));
      if (skipThis) {
        skipWhitespace();
        skipValue(depth);
      } else if (field >= 0) {
        parseValueTyped(target, field, depth);
      } else if (dispatchUnknown) {
        if (key == null) {
          key = stringFrom(keyStart, keyEnd);
        }
        parseValueLegacy(target, key, depth);
      } else {
        skipWhitespace();
        skipValue(depth);
      }

      skipWhitespace();
      if (pos >= len) throw error("Unterminated object");
      byte nc = src[pos];
      if (nc == ',') { pos++; continue; }
      if (nc == '}') { pos++; target.raw(src, objectStart, pos); return; }
      throw error("Expected [,] or [}] but found [" + (char) (nc & 0xFF) + "]");
    }
  }

  private Object parsePolymorphicObject(JSONPolymorphicObserver<?> poly, int depth) {
    if (depth > maxNestingDepth) {
      throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
    }
    if (src[pos] != '{') {
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

    parseObjectBody(child, depth, discriminatorKey.getBytes(StandardCharsets.UTF_8));
    return child.finish();
  }

  private String parseString() {
    expect('"');
    int start = pos;
    int p = pos;
    long highBits = 0;
    // SWAR scan: 8 bytes per step until a quote, backslash, or control byte appears, tracking whether
    // the slice contains non-ASCII bytes along the way.
    while (p + 8 <= len) {
      long word = (long) LONGS.get(src, p);
      long stop = stopBits(word);
      if (stop != 0) {
        highBits |= word & 0x8080808080808080L & (Long.lowestOneBit(stop) - 1);
        p += Long.numberOfTrailingZeros(stop) >>> 3;
        break;
      }
      highBits |= word & 0x8080808080808080L;
      p += 8;
    }
    boolean nonASCII = highBits != 0;
    while (p < len) {
      int b = src[p] & 0xFF;
      if (b == '"') {
        // ASCII slices decode straight off the input; non-ASCII slices decode through the reused char
        // scratch to avoid the JDK decoder's intermediate buffer.
        String s = nonASCII ? utf16Slice(start, p)
            : new String(src, start, p - start, StandardCharsets.ISO_8859_1);
        pos = p + 1;
        return s;
      }
      if (b == '\\') {
        return decodeEscapedString(start);
      }
      if (b < 0x20) {
        pos = p;
        throw error("Unescaped control character [U+" + String.format("%04X", b) + "] in string");
      }
      if (b >= 0x80) {
        nonASCII = true;
      }
      p++;
    }
    pos = p;
    throw error("Unterminated string");
  }

  /**
   * Decodes a non-ASCII, escape-free slice into the reused char scratch and builds the String in one
   * exact-size step. Malformed sequences are replaced with U+FFFD using the JDK decoder's
   * maximal-subpart rules (overlong forms and encoded surrogates rejected), matching what
   * {@code new String(bytes, UTF_8)} produced before.
   */
  private String utf16Slice(int start, int end) {
    int count = end - start;
    char[] out = CHAR_SCRATCH.get();
    if (out.length < count) {
      out = new char[Math.max(512, count)];
      CHAR_SCRATCH.set(out);
    }
    int n = 0;
    int p = start;
    while (p < end) {
      int b = src[p] & 0xFF;
      if (b < 0x80) {
        out[n++] = (char) b;
        p++;
      } else if (b < 0xC2) {
        out[n++] = '�';
        p++;
      } else if (b < 0xE0) {
        if (p + 1 < end && (src[p + 1] & 0xC0) == 0x80) {
          out[n++] = (char) (((b & 0x1F) << 6) | (src[p + 1] & 0x3F));
          p += 2;
        } else {
          out[n++] = '�';
          p++;
        }
      } else if (b < 0xF0) {
        int b2 = p + 1 < end ? src[p + 1] & 0xFF : -1;
        if (b2 < 0x80 || b2 > 0xBF || (b == 0xE0 && b2 < 0xA0) || (b == 0xED && b2 > 0x9F)) {
          out[n++] = '�';
          p++;
        } else if (p + 2 >= end || (src[p + 2] & 0xC0) != 0x80) {
          out[n++] = '�';
          p += 2;
        } else {
          out[n++] = (char) (((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (src[p + 2] & 0x3F));
          p += 3;
        }
      } else if (b < 0xF5) {
        int b2 = p + 1 < end ? src[p + 1] & 0xFF : -1;
        if (b2 < 0x80 || b2 > 0xBF || (b == 0xF0 && b2 < 0x90) || (b == 0xF4 && b2 > 0x8F)) {
          out[n++] = '�';
          p++;
        } else if (p + 2 >= end || (src[p + 2] & 0xC0) != 0x80) {
          out[n++] = '�';
          p += 2;
        } else if (p + 3 >= end || (src[p + 3] & 0xC0) != 0x80) {
          out[n++] = '�';
          p += 3;
        } else {
          int cp = ((b & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((src[p + 2] & 0x3F) << 6) | (src[p + 3] & 0x3F);
          out[n++] = Character.highSurrogate(cp);
          out[n++] = Character.lowSurrogate(cp);
          p += 4;
        }
      } else {
        out[n++] = '�';
        p++;
      }
    }
    return new String(out, 0, n);
  }

  /** Marks the high bit of every byte in {@code word} that is a quote, backslash, or control byte. */
  private static long stopBits(long word) {
    long quote = word ^ 0x2222222222222222L;
    long backslash = word ^ 0x5C5C5C5C5C5C5C5CL;
    return (((quote - 0x0101010101010101L) & ~quote)
        | ((backslash - 0x0101010101010101L) & ~backslash)
        | ((word - 0x2020202020202020L) & ~word)) & 0x8080808080808080L;
  }

  private <T> void parseValueLegacy(JSONObserver<T> target, String key, int depth) {
    skipWhitespace();
    if (pos >= len) throw error("Unexpected end of input");

    byte c = src[pos];
    switch (c) {
      case '"' -> target.string(key, parseString());
      case 't' -> { parseLiteral("true"); target.bool(key, true); }
      case 'f' -> { parseLiteral("false"); target.bool(key, false); }
      case 'n' -> { parseLiteral("null"); target.nullValue(key); }
      case '{' -> {
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
      case '[' -> {
        @SuppressWarnings("unchecked")
        JSONArrayObserver<Object> child = (JSONArrayObserver<Object>) target.beginArray(key);
        parseArrayInto(child, depth + 1);
        target.array(key, child.finish());
      }
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) {
          switch (parseNumberValue()) {
            case NUMBER_LONG -> target.integer(key, numberLong);
            case NUMBER_BIG_INTEGER -> target.bigInteger(key, numberBigInteger());
            default -> target.decimal(key, numberBigDecimal());
          }
        } else {
          throw error("Unexpected character [" + (char) (c & 0xFF) + "]");
        }
      }
    }
  }

  private <T> void parseValueTyped(JSONObserver<T> target, int field, int depth) {
    skipWhitespace();
    if (pos >= len) throw error("Unexpected end of input");

    byte c = src[pos];
    switch (c) {
      case '"' -> target.string(field, parseString());
      case 't' -> { parseLiteral("true"); target.bool(field, true); }
      case 'f' -> { parseLiteral("false"); target.bool(field, false); }
      case 'n' -> { parseLiteral("null"); target.nullValue(field); }
      case '{' -> {
        switch (target.beginObject(field)) {
          case JSONPolymorphicObserver<?> poly ->
              target.object(field, parsePolymorphicObject(poly, depth + 1));
          case JSONObserver<?> obs -> {
            @SuppressWarnings("unchecked")
            JSONObserver<Object> child = (JSONObserver<Object>) obs;
            parseObjectInto(child, depth + 1);
            target.object(field, child.finish());
          }
        }
      }
      case '[' -> {
        @SuppressWarnings("unchecked")
        JSONArrayObserver<Object> child = (JSONArrayObserver<Object>) target.beginArray(field);
        parseArrayInto(child, depth + 1);
        target.array(field, child.finish());
      }
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) {
          switch (parseNumberValue()) {
            case NUMBER_LONG -> target.integer(field, numberLong);
            case NUMBER_BIG_INTEGER -> target.bigInteger(field, numberBigInteger());
            default -> target.decimal(field, numberBigDecimal());
          }
        } else {
          throw error("Unexpected character [" + (char) (c & 0xFF) + "]");
        }
      }
    }
  }

  /**
   * Rebuilds the diagnostic {@code $...} path for {@code errorPos} by re-walking the input. Slow-path
   * only — called when constructing an exception. Everything before {@code errorPos} parsed successfully,
   * so the walk may assume well-formed input up to that point.
   */
  private String pathAt(int errorPos) {
    StringBuilder path = new StringBuilder("$");
    try {
      int p = 0;
      while (p < len && p < errorPos && isWhitespace(src[p])) p++;
      if (p < len && src[p] == '{') {
        walkValue(p, errorPos, path);
      }
    } catch (RuntimeException ignored) {
      // Path reconstruction must never mask the original error.
    }
    return path.toString();
  }

  /**
   * Walks the value starting at {@code p}. Returns the offset after the value, or {@code -1} if
   * {@code errorPos} falls inside it — in which case {@code path} holds the segments leading to it.
   */
  private int walkValue(int p, int errorPos, StringBuilder path) {
    if (p >= errorPos) {
      return -1;
    }
    byte c = src[p];
    if (c == '{') {
      p++;
      while (p < len) {
        while (p < len && isWhitespace(src[p])) p++;
        if (p >= len || p >= errorPos) return -1;
        if (src[p] == '}') return p + 1;
        // Key
        int keyStart = p + 1;
        int keyEnd = keyEndOfStringTolerant(p);
        if (keyEnd < 0 || keyEnd > errorPos) return -1;
        String key = keyText(keyStart, keyEnd - 1);
        p = keyEnd;
        while (p < len && isWhitespace(src[p])) p++;
        if (p >= len || p >= errorPos || src[p] != ':') return -1;
        p++;
        while (p < len && isWhitespace(src[p])) p++;
        int mark = path.length();
        path.append('.').append(key);
        int end = walkValue(p, errorPos, path);
        if (end < 0) return -1;
        path.setLength(mark);
        p = end;
        while (p < len && isWhitespace(src[p])) p++;
        if (p >= len || p >= errorPos) return -1;
        if (src[p] == ',') { p++; continue; }
        if (src[p] == '}') return p + 1;
        return -1;
      }
      return -1;
    }
    if (c == '[') {
      p++;
      int index = 0;
      while (p < len) {
        while (p < len && isWhitespace(src[p])) p++;
        if (p >= len) return -1;
        if (src[p] == ']') return p + 1;
        // The real parser records the element index before examining the value's first character, so an
        // error at the value start belongs to the element — append before the errorPos check.
        int mark = path.length();
        path.append('[').append(index).append(']');
        int end = walkValue(p, errorPos, path);
        if (end < 0) return -1;
        path.setLength(mark);
        p = end;
        while (p < len && isWhitespace(src[p])) p++;
        if (p >= len || p >= errorPos) return -1;
        if (src[p] == ',') { p++; index++; continue; }
        if (src[p] == ']') return p + 1;
        return -1;
      }
      return -1;
    }
    if (c == '"') {
      int end = keyEndOfStringTolerant(p);
      return (end < 0 || end > errorPos) ? -1 : end;
    }
    // Literal or number: scan the token.
    while (p < len) {
      byte b = src[p];
      if (b == ',' || b == '}' || b == ']' || isWhitespace(b)) break;
      p++;
    }
    return p > errorPos ? -1 : p;
  }

  /** Decoded key text for path display; escapes are rare, so a tolerant inline decode suffices. */
  private String keyText(int start, int end) {
    for (int i = start; i < end; i++) {
      if (src[i] == '\\') {
        int savedPos = pos;
        try {
          String decoded = decodeEscapedString(start);
          return decoded;
        } finally {
          pos = savedPos;
        }
      }
    }
    return stringFrom(start, end);
  }

  /** Like {@link #keyEndOfString(int)} but returns {@code -1} instead of throwing. */
  private int keyEndOfStringTolerant(int p) {
    if (p >= len || src[p] != '"') return -1;
    int q = p + 1;
    while (q < len) {
      int c = src[q++] & 0xFF;
      if (c == '"') return q;
      if (c == '\\') {
        if (q >= len) return -1;
        q++;
      }
    }
    return -1;
  }

  private String scanForDiscriminator(String discriminatorKey) {
    int p = pos;
    if (src[p] != '{') throw error("Scan-ahead expected [{]");
    p++;
    int braceDepth = 1;
    int bracketDepth = 0;

    while (p < len && braceDepth > 0) {
      byte c = src[p];
      if (isWhitespace(c)) { p++; continue; }

      if (braceDepth == 1 && bracketDepth == 0 && c == '"') {
        int keyStart = p;
        int keyEnd = keyEndOfString(keyStart);
        String key = scanStringAt(keyStart);
        p = keyEnd;
        while (p < len && isWhitespace(src[p])) p++;
        if (p >= len || src[p] != ':') throw error("Scan-ahead expected [:] after key");
        p++;
        while (p < len && isWhitespace(src[p])) p++;
        if (key.equals(discriminatorKey)) {
          if (p >= len || src[p] != '"') {
            throw error("Discriminator value for [" + discriminatorKey + "] must be a string");
          }
          return scanStringAt(p);
        }
        p = skipValueAt(p);
        while (p < len && isWhitespace(src[p])) p++;
        if (p < len && src[p] == ',') p++;
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

  /** Decodes the string starting at {@code p} (which must be a quote) without moving {@link #pos}. */
  private String scanStringAt(int p) {
    int savedPos = pos;
    try {
      pos = p;
      return parseString();
    } finally {
      pos = savedPos;
    }
  }

  private int skipContainerAt(int p, char open, char close) {
    int depth = 0;
    while (p < len) {
      byte c = src[p];
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

  /**
   * Validates and skips a JSON literal ({@code true}/{@code false}/{@code null}) at {@code p}, returning
   * the position after it. Mirrors {@link #parseLiteral(String)} so a skipped literal is held to the same
   * bytes and bounds as a parsed one — without this the skip path advanced a fixed width without checking
   * the bytes or the buffer end.
   */
  private int skipLiteralAt(int p, String literal) {
    int length = literal.length();
    if (p + length > len) {
      pos = p;
      throw error("Invalid literal");
    }
    for (int i = 0; i < length; i++) {
      if (src[p + i] != literal.charAt(i)) {
        pos = p;
        throw error("Invalid literal");
      }
    }
    return p + length;
  }

  /**
   * Validates and skips a JSON number at {@code p}, returning the position after it. Enforces the same
   * grammar as {@link #parseNumberValue()} (leading-zero rule, required fraction and exponent digits) so a
   * skipped number is held to the same shape as a parsed one rather than a loose run of number characters.
   */
  private int skipNumberAt(int p) {
    if (p < len && src[p] == '-') {
      p++;
      if (p >= len) {
        pos = p;
        throw error("Number ends after [-]");
      }
    }
    if (p >= len) {
      pos = p;
      throw error("Invalid number");
    }
    byte c = src[p];
    if (c == '0') {
      p++;
      if (p < len && src[p] >= '0' && src[p] <= '9') {
        pos = p;
        throw error("Leading zeros are not allowed in numbers");
      }
    } else if (c >= '1' && c <= '9') {
      while (p < len && src[p] >= '0' && src[p] <= '9') p++;
    } else {
      pos = p;
      throw error("Invalid number");
    }
    if (p < len && src[p] == '.') {
      p++;
      int fracStart = p;
      while (p < len && src[p] >= '0' && src[p] <= '9') p++;
      if (p == fracStart) {
        pos = p;
        throw error("Number has [.] with no fractional digits");
      }
    }
    if (p < len && (src[p] == 'e' || src[p] == 'E')) {
      p++;
      if (p < len && (src[p] == '+' || src[p] == '-')) p++;
      int expStart = p;
      while (p < len && src[p] >= '0' && src[p] <= '9') p++;
      if (p == expStart) {
        pos = p;
        throw error("Number has exponent marker with no exponent digits");
      }
    }
    return p;
  }

  /** Skips the value at {@link #pos} without observer callbacks, enforcing the nesting-depth cap. */
  private void skipValue(int depth) {
    if (pos >= len) throw error("Unexpected end during scan-ahead");
    byte c = src[pos];
    if (c == '{' || c == '[') {
      int p = pos;
      int containers = 0;
      while (p < len) {
        byte b = src[p];
        if (b == '"') { p = keyEndOfString(p); continue; }
        if (b == '{' || b == '[') {
          containers++;
          if (depth + containers > maxNestingDepth) {
            pos = p;
            throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
          }
        } else if (b == '}' || b == ']') {
          containers--;
          if (containers == 0) {
            pos = p + 1;
            return;
          }
        }
        p++;
      }
      pos = len;
      throw error("Unterminated container in scan-ahead");
    }
    pos = skipValueAt(pos);
  }

  private int skipValueAt(int p) {
    while (p < len && isWhitespace(src[p])) p++;
    if (p >= len) throw error("Unexpected end during scan-ahead");
    byte c = src[p];
    return switch (c) {
      case '"' -> keyEndOfString(p);
      case '{' -> skipContainerAt(p, '{', '}');
      case '[' -> skipContainerAt(p, '[', ']');
      case 't' -> skipLiteralAt(p, "true");
      case 'f' -> skipLiteralAt(p, "false");
      case 'n' -> skipLiteralAt(p, "null");
      default -> skipNumberAt(p);
    };
  }

  private void skipWhitespace() {
    while (pos < len && isWhitespace(src[pos])) {
      pos++;
    }
  }

  private String stringFrom(int start, int end) {
    int count = end - start;
    for (int i = start; i < end; i++) {
      if (src[i] < 0) {
        return new String(src, start, count, StandardCharsets.UTF_8);
      }
    }
    return new String(src, start, count, StandardCharsets.ISO_8859_1);
  }

}
