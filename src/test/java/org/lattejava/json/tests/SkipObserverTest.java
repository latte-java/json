/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class SkipObserverTest {
  @Test
  public void skipObserverIsSingleton() {
    assertSame(SkipObserver.INSTANCE, SkipObserver.INSTANCE);
  }

  @Test
  public void skipArrayObserverIsSingleton() {
    assertSame(SkipArrayObserver.INSTANCE, SkipArrayObserver.INSTANCE);
  }

  @Test
  public void skipObserverScalarCallbacksAreNoOps() {
    var s = SkipObserver.INSTANCE;
    s.string("a", "v");
    s.integer("b", 1L);
    s.bigInteger("c", BigInteger.TEN);
    s.decimal("d", BigDecimal.ONE);
    s.bool("e", true);
    s.nullValue("f");
    s.object("g", new Object());
    s.array("h", new Object());
    assertNull(s.finish(), "skip observer finish() returns null");
  }

  @Test
  public void skipObserverBeginObjectReturnsSkipObserver() {
    assertSame(SkipObserver.INSTANCE.beginObject("x"), SkipObserver.INSTANCE);
  }

  @Test
  public void skipObserverBeginArrayReturnsSkipArrayObserver() {
    assertSame(SkipObserver.INSTANCE.beginArray("x"), SkipArrayObserver.INSTANCE);
  }

  @Test
  public void skipArrayObserverScalarCallbacksAreNoOps() {
    var s = SkipArrayObserver.INSTANCE;
    s.string("v");
    s.integer(1L);
    s.bigInteger(BigInteger.TEN);
    s.decimal(BigDecimal.ONE);
    s.bool(true);
    s.nullValue();
    s.object(new Object());
    s.array(new Object());
    assertNull(s.finish(), "skip array observer finish() returns null");
  }

  @Test
  public void skipArrayObserverBeginObjectReturnsSkipObserver() {
    assertSame(SkipArrayObserver.INSTANCE.beginObject(), SkipObserver.INSTANCE);
  }

  @Test
  public void skipArrayObserverBeginArrayReturnsSkipArrayObserver() {
    assertSame(SkipArrayObserver.INSTANCE.beginArray(), SkipArrayObserver.INSTANCE);
  }
}
