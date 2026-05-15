/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class NumbersTest {
  @Test
  public void toByteExactAcceptsByteRangeValues() {
    assertEquals(Numbers.toByteExact(0L), (byte) 0);
    assertEquals(Numbers.toByteExact(127L), Byte.MAX_VALUE);
    assertEquals(Numbers.toByteExact(-128L), Byte.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[128\\].*\\[byte\\].*")
  public void toByteExactRejectsAboveRange() {
    Numbers.toByteExact(128L);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[-129\\].*\\[byte\\].*")
  public void toByteExactRejectsBelowRange() {
    Numbers.toByteExact(-129L);
  }

  @Test
  public void toShortExactAcceptsShortRangeValues() {
    assertEquals(Numbers.toShortExact(0L), (short) 0);
    assertEquals(Numbers.toShortExact(32767L), Short.MAX_VALUE);
    assertEquals(Numbers.toShortExact(-32768L), Short.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[32768\\].*\\[short\\].*")
  public void toShortExactRejectsAboveRange() {
    Numbers.toShortExact(32768L);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[-32769\\].*\\[short\\].*")
  public void toShortExactRejectsBelowRange() {
    Numbers.toShortExact(-32769L);
  }
}
