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
 * @param <T> the constructed Java value type produced by {@link #finish()}
 * @author Brian Pontarelli
 */
public interface JSONObserver<T> {
  JSONArrayObserver<?> beginArray(String key);

  Object beginObject(String key);

  void bigInteger(String key, BigInteger value);

  void bool(String key, boolean value);

  void decimal(String key, BigDecimal value);

  T finish();

  void integer(String key, long value);

  void nullValue(String key);

  void object(String key, Object value);

  void string(String key, String value);

  void array(String key, Object value);
}
