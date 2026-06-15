/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Observer driven by {@link JSONParser} during deserialization of a JSON object. The annotation processor
 * generates one implementation per {@link JSON @JSON} record or class. Numeric values are delivered into
 * typed callbacks based on the parser's classification of the raw digit-run; observers should narrow
 * further only through explicit, throwing JDK calls (e.g. {@code Math.toIntExact}).
 *
 * <p>Dispatch is two-tier. The parser first offers each raw key to {@link #field(byte[], int, int)};
 * a non-negative field ordinal routes the value through the allocation-free int-keyed callbacks.
 * A negative ordinal marks the key unknown: when {@link #dispatchUnknown()} is {@code true} the parser
 * materializes the key {@link String} and delivers the value through the String-keyed callbacks (the
 * capture path used by {@code @JSONCatchAll}, strict mode, map observers, and hand-written observers);
 * when {@code false} the parser skips the value without materializing anything.
 *
 * @param <T> the constructed Java value type produced by {@link #finish()}
 * @author Brian Pontarelli
 */
public non-sealed interface JSONObserver<T> extends JSONObjectHandler {
  JSONArrayObserver<?> beginArray(String key);

  default JSONArrayObserver<?> beginArray(int field) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  /**
   * Called for a nested JSON object value. Returns either a {@link JSONObserver} for a concrete type
   * or a {@link JSONPolymorphicObserver} for a sealed hierarchy; the parser detects which and routes
   * accordingly. Must not return {@code null}.
   */
  JSONObjectHandler beginObject(String key);

  default JSONObjectHandler beginObject(int field) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void bigInteger(String key, BigInteger value);

  default void bigInteger(int field, BigInteger value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void bool(String key, boolean value);

  default void bool(int field, boolean value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void decimal(String key, BigDecimal value);

  default void decimal(int field, BigDecimal value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  /**
   * Whether the parser should materialize unknown keys and deliver their values through the String-keyed
   * callbacks ({@code true}), or skip the value without allocating ({@code false}). Defaults to
   * {@code true} so arbitrary implementations receive every key; generated lenient observers override to
   * {@code false}.
   */
  default boolean dispatchUnknown() {
    return true;
  }

  /**
   * Matches a raw, escape-free key against this observer's known field set without allocating. Returns a
   * non-negative field ordinal, or {@code -1} for unknown. Generated observers compare against
   * compile-time key byte constants.
   */
  default int field(byte[] src, int offset, int length) {
    return -1;
  }

  /** {@link #field(byte[], int, int)} fallback for keys that contained escape sequences. */
  default int fieldOf(String key) {
    return -1;
  }

  T finish();

  void integer(String key, long value);

  default void integer(int field, long value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void nullValue(String key);

  default void nullValue(int field) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void object(String key, Object value);

  default void object(int field, Object value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void string(String key, String value);

  default void string(int field, String value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }

  void array(String key, Object value);

  default void array(int field, Object value) {
    throw new JSONProcessingException("Unexpected typed dispatch for field [" + field + "]");
  }
}
