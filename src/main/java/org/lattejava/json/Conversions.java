/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * String-form parsers for {@code @JSON} component types whose wire form is a JSON string (enums,
 * {@code UUID}, the ISO-8601 {@code java.time} types, and {@code @JSONField(asString)} user types). Each method wraps
 * the JDK's
 * {@link IllegalArgumentException} / {@link java.time.DateTimeException} in
 * {@link JSONProcessingException} so all parse failures share one exception type. Codegen calls these
 * instead of inlining try/catch at every site.
 *
 * @author Brian Pontarelli
 */
public final class Conversions {
  private Conversions() {
  }

  /**
   * Builds a {@code @JSONField(asString)} member's value from its JSON string form. {@code factory} is the target
   * type's public single-{@code String} constructor, passed by codegen as a {@code Type::new} method reference, and
   * {@code type} is that type's simple name for the message. Any failure the constructor raises is wrapped in
   * {@link JSONProcessingException} so an arbitrary user type's parse failure surfaces exactly like a malformed
   * {@code UUID} or {@code Instant} does.
   */
  public static <T> T fromString(Function<String, T> factory, String type, String value) {
    try {
      return factory.apply(value);
    } catch (RuntimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid " + type, e);
    }
  }

  /**
   * Decodes the verbatim JSON slice {@code [start, end)} for a {@code @JSONRaw} member: ISO-8859-1 for a pure-ASCII
   * slice, UTF-8 otherwise. This mirrors the private {@code JSONParser.stringFrom} helper's handling of unescaped
   * slices; the ASCII pre-scan is kept here for consistency with that method, not because it is faster than decoding
   * straight to UTF-8.
   */
  public static String rawString(byte[] src, int start, int end) {
    for (int i = start; i < end; i++) {
      if (src[i] < 0) {
        return new String(src, start, end - start, StandardCharsets.UTF_8);
      }
    }
    return new String(src, start, end - start, StandardCharsets.ISO_8859_1);
  }

  public static Duration toDuration(String value) {
    try {
      return Duration.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid Duration", e);
    }
  }

  public static <E extends Enum<E>> E toEnum(Class<E> type, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw new JSONProcessingException(
          "Value [" + value + "] is not a constant of enum [" + type.getSimpleName() + "]", e);
    }
  }

  public static Instant toInstant(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid Instant", e);
    }
  }

  public static LocalDate toLocalDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid LocalDate", e);
    }
  }

  public static LocalDateTime toLocalDateTime(String value) {
    try {
      return LocalDateTime.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid LocalDateTime", e);
    }
  }

  public static OffsetDateTime toOffsetDateTime(String value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid OffsetDateTime", e);
    }
  }

  public static Period toPeriod(String value) {
    try {
      return Period.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid Period", e);
    }
  }

  public static UUID toUUID(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid UUID", e);
    }
  }

  public static ZonedDateTime toZonedDateTime(String value) {
    try {
      return ZonedDateTime.parse(value);
    } catch (DateTimeException e) {
      throw new JSONProcessingException("Value [" + value + "] is not a valid ZonedDateTime", e);
    }
  }
}
