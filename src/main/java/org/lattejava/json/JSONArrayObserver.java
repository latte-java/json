/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Observer driven by {@link JSONParser} during deserialization of a JSON array. Element callbacks are
 * positional — no key parameter. Returned from a parent {@link JSONObserver#beginArray(String)} and
 * consumed in a single pass.
 *
 * @param <T> the constructed Java value type produced by {@link #finish()}
 * @author Brian Pontarelli
 */
public interface JSONArrayObserver<T> {
  JSONArrayObserver<?> beginArray();

  /**
   * Called for a nested JSON object array element. Returns either a {@link JSONObserver} for a concrete
   * type or a {@link JSONPolymorphicObserver} for a sealed hierarchy. Must not return {@code null}.
   */
  JSONObjectHandler beginObject();

  void bigInteger(BigInteger value);

  void bool(boolean value);

  void decimal(BigDecimal value);

  T finish();

  void integer(long value);

  void nullValue();

  void object(Object value);

  void string(String value);

  void array(Object value);
}
