/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Fluent writer for JSON arrays, mirroring {@link JSONBuilder}. Unlike object members, array elements are
 * positional, so {@code null} elements are emitted as {@code null} (never omitted). {@link #build()}
 * decodes to a {@link String}; {@link #buildBytes()} returns UTF-8 bytes. Generated companion code uses
 * this to serialize {@code List}/{@code Set} fields.
 *
 * @author Brian Pontarelli
 */
public final class JSONArrayBuilder {
  private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);
  private boolean first = true;

  public JSONArrayBuilder() {
    out.write('[');
  }

  public JSONArrayBuilder bigInteger(BigInteger value) {
    if (value == null) {
      return nullValue();
    }
    sep();
    writeRaw(value.toString());
    return this;
  }

  public JSONArrayBuilder bool(boolean value) {
    sep();
    writeRaw(value ? "true" : "false");
    return this;
  }

  public String build() {
    return new String(buildBytes(), StandardCharsets.UTF_8);
  }

  public byte[] buildBytes() {
    out.write(']');
    return out.toByteArray();
  }

  public JSONArrayBuilder decimal(BigDecimal value) {
    if (value == null) {
      return nullValue();
    }
    sep();
    writeRaw(value.toPlainString());
    return this;
  }

  public JSONArrayBuilder integer(long value) {
    sep();
    writeRaw(Long.toString(value));
    return this;
  }

  public JSONArrayBuilder nullValue() {
    sep();
    writeRaw("null");
    return this;
  }

  public JSONArrayBuilder raw(String rawJson) {
    if (rawJson == null) {
      return nullValue();
    }
    sep();
    writeRaw(rawJson);
    return this;
  }

  public JSONArrayBuilder string(String value) {
    if (value == null) {
      return nullValue();
    }
    sep();
    writeString(value);
    return this;
  }

  private void sep() {
    if (first) {
      first = false;
    } else {
      out.write(',');
    }
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
