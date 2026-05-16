/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Range-checked narrowing helpers for primitive types not covered by the JDK's {@code Math.to*Exact}
 * methods. Each method throws {@link JSONProcessingException} when the source value is outside the target
 * type's range. Codegen calls these instead of inlining the range check at every narrowing site.
 *
 * @author Brian Pontarelli
 */
public final class Numbers {
  private Numbers() {
  }

  public static byte toByteExact(long value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new JSONProcessingException(
          "Value [" + value + "] out of range for [byte]");
    }
    return (byte) value;
  }

  public static short toShortExact(long value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new JSONProcessingException(
          "Value [" + value + "] out of range for [short]");
    }
    return (short) value;
  }
}
