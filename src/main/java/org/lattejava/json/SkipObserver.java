/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Stateless singleton {@link JSONObserver} that discards every callback. Used by generated code as the
 * {@code default} target of a parent's {@code beginObject} switch under {@link JSON @JSON}'s lenient
 * default policy: unknown JSON objects are absorbed and discarded.
 *
 * @author Brian Pontarelli
 */
public final class SkipObserver implements JSONObserver<Object> {
  public static final SkipObserver INSTANCE = new SkipObserver();

  private SkipObserver() {
  }

  @Override
  public JSONArrayObserver<?> beginArray(String key) {
    return SkipArrayObserver.INSTANCE;
  }

  @Override
  public JSONObserver<?> beginObject(String key) {
    return INSTANCE;
  }

  @Override public void bigInteger(String key, BigInteger value) {}
  @Override public void bool(String key, boolean value) {}
  @Override public void decimal(String key, BigDecimal value) {}

  @Override
  public Object finish() {
    return null;
  }

  @Override public void integer(String key, long value) {}
  @Override public void nullValue(String key) {}
  @Override public void object(String key, Object value) {}
  @Override public void string(String key, String value) {}
  @Override public void array(String key, Object value) {}
}
