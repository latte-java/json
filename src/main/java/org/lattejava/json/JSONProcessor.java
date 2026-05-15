/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Simple class for JSON serialization and deserialization. This is thread-safe.
 *
 * @author Daniel DeGroff
 */
public class JSONProcessor {
  public static final int DEFAULT_MAX_ARRAY_ELEMENTS = 10000;

  /**
   * Default object/array fan-out caps applied during parse.
   */
  public static final int DEFAULT_MAX_OBJECT_MEMBERS = 1000;
  private final boolean allowDuplicateJSONKeys;
  private final int maxArrayElements;
  private final int maxNestingDepth;
  private final int maxNumberLength;
  private final int maxObjectMembers;

  /**
   * Constructs a {@code JSONProcessor} with defaults (maxNestingDepth=16, maxNumberLength=1000, maxObjectMembers=1000,
   * maxArrayElements=10000, allowDuplicateJSONKeys=false).
   */
  public JSONProcessor() {
    this(16, 1000, DEFAULT_MAX_OBJECT_MEMBERS, DEFAULT_MAX_ARRAY_ELEMENTS, false);
  }

  /**
   * Constructs a {@code JSONProcessor} with explicit depth, number, and duplicate-key defenses; object/array fan-out
   * caps default to {@value #DEFAULT_MAX_OBJECT_MEMBERS} and {@value #DEFAULT_MAX_ARRAY_ELEMENTS}.
   *
   * @param maxNestingDepth        maximum JSON object/array nesting depth (must be &gt; 0)
   * @param maxNumberLength        maximum digit-run length of a single JSON number (integer + decimal + exponent
   *                               digits; sign chars excluded). Must be &gt; 0.
   * @param allowDuplicateJSONKeys when {@code false} (default), duplicate JSON object member names cause
   *                               {@link JSONProcessingException}.
   */
  public JSONProcessor(int maxNestingDepth, int maxNumberLength, boolean allowDuplicateJSONKeys) {
    this(maxNestingDepth, maxNumberLength, DEFAULT_MAX_OBJECT_MEMBERS, DEFAULT_MAX_ARRAY_ELEMENTS, allowDuplicateJSONKeys);
  }

  /**
   * Constructs a {@code JSONProcessor} with explicit defenses.
   *
   * @param maxNestingDepth        maximum JSON object/array nesting depth (must be &gt; 0)
   * @param maxNumberLength        maximum digit-run length of a single JSON number (integer + decimal + exponent
   *                               digits; sign chars excluded). Must be &gt; 0.
   * @param maxObjectMembers       maximum number of members in a single JSON object (must be &gt; 0). Bounds the cost
   *                               of building wide objects at parse time and downstream consumers that walk them.
   * @param maxArrayElements       maximum number of elements in a single JSON array (must be &gt; 0). Bounds the cost
   *                               of building wide arrays at parse time and downstream consumers that walk them.
   * @param allowDuplicateJSONKeys when {@code false} (default), duplicate JSON object member names cause
   *                               {@link JSONProcessingException}.
   */
  public JSONProcessor(int maxNestingDepth, int maxNumberLength, int maxObjectMembers,
                       int maxArrayElements, boolean allowDuplicateJSONKeys) {
    if (maxNestingDepth <= 0) {
      throw new IllegalArgumentException("maxNestingDepth must be > 0 but found [" + maxNestingDepth + "]");
    }
    if (maxNumberLength <= 0) {
      throw new IllegalArgumentException("maxNumberLength must be > 0 but found [" + maxNumberLength + "]");
    }
    if (maxObjectMembers <= 0) {
      throw new IllegalArgumentException("maxObjectMembers must be > 0 but found [" + maxObjectMembers + "]");
    }
    if (maxArrayElements <= 0) {
      throw new IllegalArgumentException("maxArrayElements must be > 0 but found [" + maxArrayElements + "]");
    }
    this.maxNestingDepth = maxNestingDepth;
    this.maxNumberLength = maxNumberLength;
    this.maxObjectMembers = maxObjectMembers;
    this.maxArrayElements = maxArrayElements;
    this.allowDuplicateJSONKeys = allowDuplicateJSONKeys;
  }

  // ---------------------------------------------------------------------
  // Serialize
  // ---------------------------------------------------------------------

  /**
   * Deserialize UTF-8 JSON bytes into a Map. The top-level JSON value MUST be an object; top-level arrays, strings,
   * numbers, booleans, or null MUST cause {@link JSONProcessingException}. JWT payloads and headers are always JSON
   * objects per RFC 7519 §7.2, so this constraint imposes no real-world limitation on the decoder.
   *
   * @param json UTF-8 JSON bytes
   * @return the parsed map (top-level JSON object)
   * @throws JSONProcessingException on malformed JSON or non-object top-level value
   */
  public Map<String, Object> deserialize(byte[] json) throws JSONProcessingException {
    if (json == null) {
      throw new JSONProcessingException("Input bytes are null");
    }
    String input = new String(json, StandardCharsets.UTF_8);
    Parser p = new Parser(input);
    p.skipWhitespace();
    if (p.pos >= p.len) {
      throw new JSONProcessingException("Empty input");
    }
    if (p.peek() != '{') {
      throw new JSONProcessingException("Expected top-level JSON object but found [" + p.peek() + "]");
    }
    Object value = p.parseValue(0);
    p.skipWhitespace();
    if (p.pos != p.len) {
      throw new JSONProcessingException("Trailing content after JSON value at position [" + p.pos + "]");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) value;
    return map;
  }

  /**
   * Serialize a map to UTF-8 JSON bytes.
   *
   * @param object the map to serialize (must not be {@code null})
   * @return UTF-8 JSON bytes
   * @throws JSONProcessingException on serialization failure
   */
  public byte[] serialize(Map<String, Object> object) throws JSONProcessingException {
    if (object == null) {
      throw new JSONProcessingException("Input map is null");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    try {
      writeMap(object, out);
    } catch (IOException e) {
      throw new JSONProcessingException("Serialization I/O failure", e);
    }
    return out.toByteArray();
  }

  private void writeList(List<?> list, ByteArrayOutputStream out) throws IOException {
    out.write('[');
    boolean first = true;
    for (Object v : list) {
      if (!first) {
        out.write(',');
      }
      first = false;
      writeValue(v, out);
    }
    out.write(']');
  }

  private void writeMap(Map<String, Object> m, ByteArrayOutputStream out) throws IOException {
    out.write('{');
    boolean first = true;
    for (Map.Entry<String, Object> e : m.entrySet()) {
      if (!first) {
        out.write(',');
      }
      first = false;
      Object k = e.getKey();
      if (!(k instanceof String keyStr)) {
        throw new JSONProcessingException("Expected String map key but it was null");
      }

      writeString(keyStr, out);
      out.write(':');
      writeValue(e.getValue(), out);
    }
    out.write('}');
  }

  /**
   * Writes a JSON string with RFC 8259 §7 escaping. Always escapes {@code "}, {@code \}, and control chars
   * {@code U+0000}-{@code U+001F}. Non-ASCII characters (including surrogate pairs forming code points beyond the BMP)
   * are emitted as raw UTF-8 bytes.
   */
  private void writeString(String s, ByteArrayOutputStream out) throws IOException {
    out.write('"');
    // Walk the string character-by-character for the special-case ASCII escapes.
    // For non-ASCII runs (including surrogate pairs), flush a substring through
    // UTF-8 so surrogate pairs encode correctly into 4-byte sequences.
    int len = s.length();
    int i = 0;
    while (i < len) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\' || c < 0x20) {
        switch (c) {
          case '"':
            out.write('\\');
            out.write('"');
            break;
          case '\\':
            out.write('\\');
            out.write('\\');
            break;
          case '\b':
            out.write('\\');
            out.write('b');
            break;
          case '\f':
            out.write('\\');
            out.write('f');
            break;
          case '\n':
            out.write('\\');
            out.write('n');
            break;
          case '\r':
            out.write('\\');
            out.write('r');
            break;
          case '\t':
            out.write('\\');
            out.write('t');
            break;
          default:
            String hex = String.format("\\u%04x", (int) c);
            out.write(hex.getBytes(StandardCharsets.UTF_8));
        }
        i++;
      } else {
        // Find a maximal run of non-special chars and emit it as UTF-8 in one shot.
        // This naturally handles surrogate pairs because String.getBytes(UTF_8)
        // sees both halves together.
        int runStart = i;
        while (i < len) {
          char d = s.charAt(i);
          if (d == '"' || d == '\\' || d < 0x20) break;
          i++;
        }
        out.write(s.substring(runStart, i).getBytes(StandardCharsets.UTF_8));
      }
    }
    out.write('"');
  }

  // ---------------------------------------------------------------------
  // Deserialize
  // ---------------------------------------------------------------------

  private void writeValue(Object value, ByteArrayOutputStream out) throws IOException {
    if (value == null) {
      out.write('n');
      out.write('u');
      out.write('l');
      out.write('l');
    } else if (value instanceof String s) {
      writeString(s, out);
    } else if (value instanceof Boolean bool) {
      String s = bool.toString();
      out.write(s.getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      out.write(value.toString().getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof BigDecimal bd) {
      out.write(bd.toPlainString().getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof Float || value instanceof Double) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new JSONProcessingException("Cannot serialize non-finite number [" + value + "]");
      }
      out.write(value.toString().getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) value;
      writeMap(m, out);
    } else if (value instanceof List<?> list) {
      writeList(list, out);
    } else {
      throw new JSONProcessingException("Unsupported value type [" + value.getClass().getName() + "]");
    }
  }

  /**
   * Single-pass recursive-descent parser.
   */
  private final class Parser {
    final int len;
    final String s;
    int pos = 0;

    Parser(String s) {
      this.s = s;
      this.len = s.length();
    }

    void expect(char c) {
      if (pos >= len) {
        throw new JSONProcessingException("Expected [" + c + "] but reached end of input");
      }
      if (s.charAt(pos) != c) {
        throw new JSONProcessingException(
            "Expected [" + c + "] but found [" + s.charAt(pos) + "] at position [" + pos + "]");
      }
      pos++;
    }

    List<Object> parseArray(int depth) {
      if (depth > maxNestingDepth) {
        throw new JSONProcessingException(
            "Maximum nesting depth [" + maxNestingDepth + "] exceeded at position [" + pos + "]");
      }
      expect('[');
      List<Object> list = new ArrayList<>();
      skipWhitespace();
      if (pos < len && s.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (true) {
        Object value = parseValue(depth);
        if (list.size() >= maxArrayElements) {
          throw new JSONProcessingException(
              "Array exceeds maxArrayElements [" + maxArrayElements + "] at position [" + pos + "]");
        }
        list.add(value);
        skipWhitespace();
        if (pos >= len) {
          throw new JSONProcessingException("Unterminated array at position [" + pos + "]");
        }
        char nc = s.charAt(pos);
        if (nc == ',') {
          pos++;
          continue;
        }
        if (nc == ']') {
          pos++;
          return list;
        }
        throw new JSONProcessingException("Expected [,] or []] at position [" + pos + "]");
      }
    }

    Boolean parseBoolean() {
      if (pos + 4 <= len && s.regionMatches(pos, "true", 0, 4)) {
        pos += 4;
        return Boolean.TRUE;
      }
      if (pos + 5 <= len && s.regionMatches(pos, "false", 0, 5)) {
        pos += 5;
        return Boolean.FALSE;
      }
      throw new JSONProcessingException("Invalid literal at position [" + pos + "]");
    }

    int parseHex4() {
      if (pos + 4 > len) {
        throw new JSONProcessingException("Truncated \\u escape");
      }
      int code = 0;
      for (int i = 0; i < 4; i++) {
        char c = s.charAt(pos++);
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = 10 + (c - 'a');
        else if (c >= 'A' && c <= 'F') d = 10 + (c - 'A');
        else throw new JSONProcessingException("Invalid hex digit [" + c + "] in \\u escape");
        code = (code << 4) | d;
      }
      return code;
    }

    Object parseNull() {
      if (pos + 4 <= len && s.regionMatches(pos, "null", 0, 4)) {
        pos += 4;
        return null;
      }
      throw new JSONProcessingException("Invalid literal at position [" + pos + "]");
    }

    Object parseNumber() {
      int start = pos;
      int digitCount = 0;
      boolean hasDecimal = false;
      boolean hasExponent = false;

      if (s.charAt(pos) == '-') {
        pos++;
        if (pos >= len) {
          throw new JSONProcessingException("Number ends after [-]");
        }
      }

      // integer part
      char c = s.charAt(pos);
      if (c == '0') {
        pos++;
        digitCount++;
      } else if (c >= '1' && c <= '9') {
        while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
          pos++;
          digitCount++;
          if (digitCount > maxNumberLength) {
            throw new JSONProcessingException(
                "Number digit-run exceeds maxNumberLength [" + maxNumberLength + "]");
          }
        }
      } else {
        throw new JSONProcessingException("Invalid number at position [" + pos + "]");
      }

      // fraction
      if (pos < len && s.charAt(pos) == '.') {
        hasDecimal = true;
        pos++;
        int fracStart = pos;
        while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
          pos++;
          digitCount++;
          if (digitCount > maxNumberLength) {
            throw new JSONProcessingException(
                "Number digit-run exceeds maxNumberLength [" + maxNumberLength + "]");
          }
        }
        if (pos == fracStart) {
          throw new JSONProcessingException("Number has [.] with no fractional digits");
        }
      }

      // exponent
      if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
        hasExponent = true;
        pos++;
        if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
          pos++;
        }
        int expStart = pos;
        while (pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
          pos++;
          digitCount++;
          if (digitCount > maxNumberLength) {
            throw new JSONProcessingException(
                "Number digit-run exceeds maxNumberLength [" + maxNumberLength + "]");
          }
        }
        if (pos == expStart) {
          throw new JSONProcessingException("Number has exponent marker with no exponent digits");
        }
      }

      try {
        if (hasDecimal || hasExponent) {
          return new BigDecimal(s.substring(start, pos));
        }
        // Long fast-path: a digit run of at most 18 chars fits in a long
        // (Long.MAX_VALUE is 19 digits; capping at 18 sidesteps overflow checks
        // and covers every realistic JWT claim including epoch-second timestamps).
        // Long.parseLong on a CharSequence range avoids the substring allocation.
        if (digitCount <= 18) {
          return Long.parseLong(s, start, pos, 10);
        }
        return new BigInteger(s.substring(start, pos));
      } catch (NumberFormatException e) {
        throw new JSONProcessingException("Invalid number [" + s.substring(start, pos) + "]", e);
      }
    }

    Map<String, Object> parseObject(int depth) {
      if (depth > maxNestingDepth) {
        throw new JSONProcessingException(
            "Maximum nesting depth [" + maxNestingDepth + "] exceeded at position [" + pos + "]");
      }
      expect('{');
      Map<String, Object> map = new LinkedHashMap<>();
      skipWhitespace();
      if (pos < len && s.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        if (pos >= len || s.charAt(pos) != '"') {
          throw new JSONProcessingException(
              "Expected string key at position [" + pos + "]");
        }
        String key = parseString();
        skipWhitespace();
        expect(':');
        Object value = parseValue(depth);
        if (!allowDuplicateJSONKeys && map.containsKey(key)) {
          throw new JSONProcessingException("Duplicate JSON key [" + key + "]");
        }
        if (map.size() >= maxObjectMembers && !map.containsKey(key)) {
          throw new JSONProcessingException(
              "Object exceeds maxObjectMembers [" + maxObjectMembers + "] at position [" + pos + "]");
        }
        map.put(key, value);
        skipWhitespace();
        if (pos >= len) {
          throw new JSONProcessingException("Unterminated object at position [" + pos + "]");
        }
        char nc = s.charAt(pos);
        if (nc == ',') {
          pos++;
          continue;
        }
        if (nc == '}') {
          pos++;
          return map;
        }
        throw new JSONProcessingException("Expected [,] or [}] at position [" + pos + "]");
      }
    }

    String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (pos < len) {
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (pos >= len) {
            throw new JSONProcessingException("Unterminated escape sequence");
          }
          char esc = s.charAt(pos++);
          switch (esc) {
            case '"':
              sb.append('"');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '/':
              sb.append('/');
              break;
            case 'b':
              sb.append('\b');
              break;
            case 'f':
              sb.append('\f');
              break;
            case 'n':
              sb.append('\n');
              break;
            case 'r':
              sb.append('\r');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'u': {
              int code = parseHex4();
              if (Character.isHighSurrogate((char) code)) {
                if (pos + 1 >= len || s.charAt(pos) != '\\' || s.charAt(pos + 1) != 'u') {
                  throw new JSONProcessingException(
                      "Lone high surrogate [\\u" + Integer.toHexString(code) + "]");
                }
                pos += 2;
                int low = parseHex4();
                if (!Character.isLowSurrogate((char) low)) {
                  throw new JSONProcessingException(
                      "High surrogate not followed by low surrogate");
                }
                sb.append((char) code);
                sb.append((char) low);
              } else if (Character.isLowSurrogate((char) code)) {
                throw new JSONProcessingException(
                    "Lone low surrogate [\\u" + Integer.toHexString(code) + "]");
              } else {
                sb.append((char) code);
              }
              break;
            }
            default:
              throw new JSONProcessingException("Invalid escape [\\" + esc + "]");
          }
        } else if (c < 0x20) {
          throw new JSONProcessingException(
              "Unescaped control character [U+" + String.format("%04X", (int) c) + "] in string");
        } else {
          sb.append(c);
        }
      }
      throw new JSONProcessingException("Unterminated string");
    }

    Object parseValue(int depth) {
      skipWhitespace();
      if (pos >= len) {
        throw new JSONProcessingException("Unexpected end of input");
      }

      char c = s.charAt(pos);
      return switch (c) {
        case '{' -> parseObject(depth + 1);
        case '[' -> parseArray(depth + 1);
        case '"' -> parseString();
        case 't', 'f' -> parseBoolean();
        case 'n' -> parseNull();
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield parseNumber();
          }
          throw new JSONProcessingException("Unexpected character [" + c + "] at position [" + pos + "]");
        }
      };
    }

    char peek() {
      return s.charAt(pos);
    }

    void skipWhitespace() {
      while (pos < len) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }
  }
}
