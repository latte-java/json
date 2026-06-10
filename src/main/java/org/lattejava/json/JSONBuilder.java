/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Fluent builder for JSON objects. Writes UTF-8 bytes directly to a {@link ByteArrayOutputStream};
 * {@link #build()} decodes to a {@link String}, {@link #buildBytes()} returns the raw bytes. Generated
 * companion code calls these methods in source order; field order on the wire matches Java declaration
 * order.
 *
 * <p>By default null values and {@code null}-passed raw JSON members are omitted, matching
 * {@link JSON @JSON}'s {@code omitNulls = true} default. Pass {@code false} to the constructor to emit
 * them faithfully.
 *
 * <p><b>Single-use:</b> a builder instance must be finalized exactly once. Calling
 * {@link #build()} or {@link #buildBytes()} more than once on the same instance produces
 * malformed JSON. Generated companion code constructs a fresh builder per serialization call.
 *
 * @author Brian Pontarelli
 */
public final class JSONBuilder {
  private boolean first = true;
  private final boolean omitNulls;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

  public JSONBuilder() {
    this(true);
  }

  public JSONBuilder(boolean omitNulls) {
    this.omitNulls = omitNulls;
    out.write('{');
  }

  /**
   * Writes {@code value} at {@code key} as its natural JSON shape, recursing into {@code Map}/{@code List}. Used to
   * spread a {@code @JSONCatchAll} map; throws on a value type outside the natural shapes that the parser produces.
   */
  public JSONBuilder any(String key, Object value) {
    switch (value) {
      case null -> nullValue(key);
      case String s -> string(key, s);
      case Boolean b -> bool(key, b);
      case BigInteger bi -> bigInteger(key, bi);
      case BigDecimal bd -> decimal(key, bd);
      case Double d -> decimal(key, d);
      case Float f -> decimal(key, f);
      case Number n -> integer(key, n);
      case Map<?, ?> m -> {
        JSONBuilder sub = new JSONBuilder(omitNulls);
        for (Map.Entry<?, ?> e : m.entrySet()) {
          sub.any(String.valueOf(e.getKey()), e.getValue());
        }
        object(key, sub.build());
      }
      case List<?> list -> {
        JSONArrayBuilder sub = new JSONArrayBuilder(omitNulls);
        for (Object element : list) {
          sub.any(element);
        }
        array(key, sub.build());
      }
      default -> throw new JSONProcessingException("Unsupported catch-all value type [" + value.getClass() + "]");
    }
    return this;
  }

  public JSONBuilder array(String key, String rawJson) {
    if (rawJson == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(rawJson);
    return this;
  }

  public JSONBuilder bigInteger(String key, BigInteger value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value.toString());
    return this;
  }

  public JSONBuilder bool(String key, boolean value) {
    writeKey(key);
    writeRaw(value ? "true" : "false");
    return this;
  }

  public JSONBuilder bool(String key, Boolean value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value ? "true" : "false");
    return this;
  }

  public String build() {
    return new String(buildBytes(), StandardCharsets.UTF_8);
  }

  public byte[] buildBytes() {
    out.write('}');
    return out.toByteArray();
  }

  public JSONBuilder decimal(String key, BigDecimal value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value.toPlainString());
    return this;
  }

  public JSONBuilder decimal(String key, Double value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(BigDecimal.valueOf(value).toPlainString());
    return this;
  }

  public JSONBuilder decimal(String key, Float value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(BigDecimal.valueOf(value.doubleValue()).toPlainString());
    return this;
  }

  public JSONBuilder integer(String key, long value) {
    writeKey(key);
    writeRaw(Long.toString(value));
    return this;
  }

  public JSONBuilder integer(String key, Number value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value.toString());
    return this;
  }

  public JSONBuilder nullValue(String key) {
    if (omitNulls) {
      return this;
    }
    writeKey(key);
    writeRaw("null");
    return this;
  }

  public JSONBuilder object(String key, String rawJson) {
    if (rawJson == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(rawJson);
    return this;
  }

  public JSONBuilder string(String key, String value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeString(value);
    return this;
  }

  private JSONBuilder omittedNull(String key) {
    if (omitNulls) {
      return this;
    }
    writeKey(key);
    writeRaw("null");
    return this;
  }

  private void writeKey(String key) {
    if (first) {
      first = false;
    } else {
      out.write(',');
    }
    writeString(key);
    out.write(':');
  }

  private void writeRaw(String literal) {
    out.writeBytes(literal.getBytes(StandardCharsets.UTF_8));
  }

  private void writeString(String s) {
    out.write('"');
    int len = s.length();
    int i = 0;
    while (i < len) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\' || c < 0x20) {
        switch (c) {
          case '"'  -> { out.write('\\'); out.write('"'); }
          case '\\' -> { out.write('\\'); out.write('\\'); }
          case '\b' -> { out.write('\\'); out.write('b'); }
          case '\f' -> { out.write('\\'); out.write('f'); }
          case '\n' -> { out.write('\\'); out.write('n'); }
          case '\r' -> { out.write('\\'); out.write('r'); }
          case '\t' -> { out.write('\\'); out.write('t'); }
          default -> writeRaw(String.format("\\u%04x", (int) c));
        }
        i++;
      } else {
        int runStart = i;
        while (i < len) {
          char d = s.charAt(i);
          if (d == '"' || d == '\\' || d < 0x20) break;
          i++;
        }
        writeRaw(s.substring(runStart, i));
      }
    }
    out.write('"');
  }
}
