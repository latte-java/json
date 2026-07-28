/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class ConversionsTest {
  enum Color { RED, GREEN, BLUE }

  @Test
  public void toEnumValid() {
    assertEquals(Conversions.toEnum(Color.class, "GREEN"), Color.GREEN);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[PURPLE\\].*\\[Color\\].*")
  public void toEnumInvalid() {
    Conversions.toEnum(Color.class, "PURPLE");
  }

  @Test
  public void toUUIDValid() {
    var u = UUID.fromString("00000000-0000-0000-0000-000000000001");
    assertEquals(Conversions.toUUID("00000000-0000-0000-0000-000000000001"), u);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[not-a-uuid\\].*UUID.*")
  public void toUUIDInvalid() {
    Conversions.toUUID("not-a-uuid");
  }

  @Test
  public void timeParsersValid() {
    assertEquals(Conversions.toInstant("2026-05-16T10:15:30Z"),
        Instant.parse("2026-05-16T10:15:30Z"));
    assertEquals(Conversions.toLocalDate("2026-05-16"), LocalDate.parse("2026-05-16"));
    assertEquals(Conversions.toLocalDateTime("2026-05-16T10:15:30"),
        LocalDateTime.parse("2026-05-16T10:15:30"));
    assertEquals(Conversions.toOffsetDateTime("2026-05-16T10:15:30+01:00"),
        OffsetDateTime.parse("2026-05-16T10:15:30+01:00"));
    assertEquals(Conversions.toZonedDateTime("2026-05-16T10:15:30+01:00[Europe/Paris]"),
        ZonedDateTime.parse("2026-05-16T10:15:30+01:00[Europe/Paris]"));
    assertEquals(Conversions.toDuration("PT1H30M"), Duration.parse("PT1H30M"));
    assertEquals(Conversions.toPeriod("P1Y2M3D"), Period.parse("P1Y2M3D"));
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*\\[nope\\].*Instant.*")
  public void timeParserInvalidWraps() {
    Conversions.toInstant("nope");
  }

  @Test
  public void rawStringDecodesASCIISlice() {
    byte[] src = "xx{\"a\":1}yy".getBytes(StandardCharsets.UTF_8);
    assertEquals(Conversions.rawString(src, 2, 9), "{\"a\":1}");
  }

  @Test
  public void rawStringDecodesMultiByteSlice() {
    byte[] src = "{\"k\":\"é€漢\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(Conversions.rawString(src, 0, src.length), "{\"k\":\"é€漢\"}");
  }

  @Test
  public void rawStringOfEmptySliceIsEmpty() {
    assertEquals(Conversions.rawString("{}".getBytes(StandardCharsets.UTF_8), 1, 1), "");
  }
}
