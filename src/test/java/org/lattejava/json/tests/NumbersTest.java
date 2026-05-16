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

  @Test
  public void toIntExactBigDecimalAcceptsIntegerInRange() {
    assertEquals(Numbers.toIntExact(new BigDecimal("42")), 42);
    assertEquals(Numbers.toIntExact(new BigDecimal("-1")), -1);
    assertEquals(Numbers.toIntExact(new BigDecimal("2147483647")), Integer.MAX_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[1\\.5\\].*\\[int\\].*")
  public void toIntExactBigDecimalRejectsNonInteger() {
    Numbers.toIntExact(new BigDecimal("1.5"));
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[2147483648\\].*\\[int\\].*")
  public void toIntExactBigDecimalRejectsOutOfRange() {
    Numbers.toIntExact(new BigDecimal("2147483648"));
  }

  @Test
  public void toIntExactBigIntegerAcceptsIntegerInRange() {
    assertEquals(Numbers.toIntExact(BigInteger.valueOf(100)), 100);
    assertEquals(Numbers.toIntExact(BigInteger.valueOf(Integer.MIN_VALUE)), Integer.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[2147483648\\].*\\[int\\].*")
  public void toIntExactBigIntegerRejectsOutOfRange() {
    Numbers.toIntExact(BigInteger.valueOf(2147483648L));
  }

  @Test
  public void toIntExactLongAcceptsIntegerInRange() {
    assertEquals(Numbers.toIntExact(0L), 0);
    assertEquals(Numbers.toIntExact(Integer.MAX_VALUE), Integer.MAX_VALUE);
    assertEquals(Numbers.toIntExact(Integer.MIN_VALUE), Integer.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[2147483648\\].*\\[int\\].*")
  public void toIntExactLongRejectsOutOfRange() {
    Numbers.toIntExact(2147483648L);
  }

  @Test
  public void toLongExactBigDecimalAcceptsIntegerInRange() {
    assertEquals(Numbers.toLongExact(new BigDecimal("42")), 42L);
    assertEquals(Numbers.toLongExact(new BigDecimal("9223372036854775807")), Long.MAX_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[1\\.5\\].*\\[long\\].*")
  public void toLongExactBigDecimalRejectsNonInteger() {
    Numbers.toLongExact(new BigDecimal("1.5"));
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[9223372036854775808\\].*\\[long\\].*")
  public void toLongExactBigDecimalRejectsOutOfRange() {
    Numbers.toLongExact(new BigDecimal("9223372036854775808"));
  }

  @Test
  public void toLongExactBigIntegerAcceptsInRange() {
    assertEquals(Numbers.toLongExact(BigInteger.valueOf(Long.MAX_VALUE)), Long.MAX_VALUE);
    assertEquals(Numbers.toLongExact(BigInteger.valueOf(Long.MIN_VALUE)), Long.MIN_VALUE);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[9223372036854775808\\].*\\[long\\].*")
  public void toLongExactBigIntegerRejectsOutOfRange() {
    Numbers.toLongExact(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
  }
}
