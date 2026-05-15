/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * {@link JSONArrayObserver} that accumulates every element into an {@link ArrayList} of the element's
 * natural Java shape ({@code String}, {@code Long}, {@code BigInteger}, {@code BigDecimal},
 * {@code Boolean}, {@code null}, {@code LinkedHashMap<String, Object>} for nested objects, nested
 * {@code ArrayList<Object>} for nested arrays). One instance per array; not thread-safe.
 *
 * @author Brian Pontarelli
 */
public final class AnyArrayObserver implements JSONArrayObserver<List<Object>> {
  private final List<Object> list = new ArrayList<>();

  @Override
  public JSONArrayObserver<?> beginArray() {
    return new AnyArrayObserver();
  }

  @Override
  public Object beginObject() {
    return new AnyObjectObserver();
  }

  @Override public void bigInteger(BigInteger value) { list.add(value); }
  @Override public void bool(boolean value)          { list.add(value); }
  @Override public void decimal(BigDecimal value)    { list.add(value); }

  @Override
  public List<Object> finish() {
    return list;
  }

  @Override public void integer(long value)          { list.add(value); }
  @Override public void nullValue()                  { list.add(null); }
  @Override public void object(Object value)         { list.add(value); }
  @Override public void string(String value)         { list.add(value); }
  @Override public void array(Object value)          { list.add(value); }
}
