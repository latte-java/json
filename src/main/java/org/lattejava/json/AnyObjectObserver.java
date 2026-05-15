/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * {@link JSONObserver} that accumulates every key/value pair into a {@link LinkedHashMap} of the value's
 * natural Java shape (same mapping as {@link AnyArrayObserver}). Preserves JSON-object insertion order.
 * One instance per JSON object; not thread-safe.
 *
 * @author Brian Pontarelli
 */
public final class AnyObjectObserver implements JSONObserver<Map<String, Object>> {
  private final Map<String, Object> map = new LinkedHashMap<>();

  @Override
  public JSONArrayObserver<?> beginArray(String key) {
    return new AnyArrayObserver();
  }

  @Override
  public Object beginObject(String key) {
    return new AnyObjectObserver();
  }

  @Override public void bigInteger(String key, BigInteger value) { map.put(key, value); }
  @Override public void bool(String key, boolean value)          { map.put(key, value); }
  @Override public void decimal(String key, BigDecimal value)    { map.put(key, value); }

  @Override
  public Map<String, Object> finish() {
    return map;
  }

  @Override public void integer(String key, long value)          { map.put(key, value); }
  @Override public void nullValue(String key)                    { map.put(key, null); }
  @Override public void object(String key, Object value)         { map.put(key, value); }
  @Override public void string(String key, String value)         { map.put(key, value); }
  @Override public void array(String key, Object value)          { map.put(key, value); }
}
