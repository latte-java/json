/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Stateless singleton {@link JSONArrayObserver} that discards every callback. Used by generated code as
 * the {@code default} target of a parent's {@code beginArray} switch under {@link JSON @JSON}'s lenient
 * default policy: unknown JSON arrays are absorbed and discarded.
 *
 * @author Brian Pontarelli
 */
public final class SkipArrayObserver implements JSONArrayObserver<Object> {
  public static final SkipArrayObserver INSTANCE = new SkipArrayObserver();

  private SkipArrayObserver() {
  }

  @Override
  public JSONArrayObserver<?> beginArray() {
    return INSTANCE;
  }

  @Override
  public Object beginObject() {
    return SkipObserver.INSTANCE;
  }

  @Override public void bigInteger(BigInteger value) {}
  @Override public void bool(boolean value) {}
  @Override public void decimal(BigDecimal value) {}

  @Override
  public Object finish() {
    return null;
  }

  @Override public void integer(long value) {}
  @Override public void nullValue() {}
  @Override public void object(Object value) {}
  @Override public void string(String value) {}
  @Override public void array(Object value) {}
}
