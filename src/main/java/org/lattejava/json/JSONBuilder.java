/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

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
 * @author Brian Pontarelli
 */
public final class JSONBuilder {
  private final boolean omitNulls;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
  private boolean first = true;

  public JSONBuilder() {
    this(true);
  }

  public JSONBuilder(boolean omitNulls) {
    this.omitNulls = omitNulls;
    out.write('{');
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

  public byte[] buildBytes() {
    out.write('}');
    return out.toByteArray();
  }

  public String build() {
    return new String(buildBytes(), StandardCharsets.UTF_8);
  }

  public JSONBuilder decimal(String key, BigDecimal value) {
    if (value == null) {
      return omittedNull(key);
    }
    writeKey(key);
    writeRaw(value.toPlainString());
    return this;
  }

  public JSONBuilder integer(String key, long value) {
    writeKey(key);
    writeRaw(Long.toString(value));
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
    try {
      out.write(literal.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new JSONProcessingException("Serialization I/O failure", e);
    }
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
