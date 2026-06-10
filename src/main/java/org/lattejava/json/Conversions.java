/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * String-form parsers for {@code @JSON} component types whose wire form is a JSON string (enums,
 * {@code UUID}, and the ISO-8601 {@code java.time} types). Each method wraps the JDK's
 * {@link IllegalArgumentException} / {@link java.time.DateTimeException} in
 * {@link JSONProcessingException} so all parse failures share one exception type. Codegen calls these
 * instead of inlining try/catch at every site.
 *
 * @author Brian Pontarelli
 */
public final class Conversions {
  private Conversions() {
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
