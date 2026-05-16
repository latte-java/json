/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class TimeCodegenTest {
  static ProcessorHarness.Result extras;

  @BeforeClass
  public void compileOnce() throws Exception {
    extras = ProcessorHarness.compile("extras");
    assertTrue(extras.success(), extras.diagnostics().toString());
  }

  @Test
  public void roundTripsAllJavaTimeTypes() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> times  = loader.loadClass("demo.Times");
      Class<?> timesJson = loader.loadClass("demo.internal.TimesJSON");
      String json = "{"
          + "\"instant\":\"2026-05-16T10:15:30Z\","
          + "\"localDate\":\"2026-05-16\","
          + "\"localDateTime\":\"2026-05-16T10:15:30\","
          + "\"offsetDateTime\":\"2026-05-16T10:15:30+01:00\","
          + "\"zonedDateTime\":\"2026-05-16T10:15:30+02:00[Europe/Paris]\","
          + "\"duration\":\"PT1H30M\","
          + "\"period\":\"P1Y2M3D\"}";
      Object obj = timesJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(times.getMethod("instant").invoke(obj),
          java.time.Instant.parse("2026-05-16T10:15:30Z"));
      assertEquals(times.getMethod("period").invoke(obj),
          java.time.Period.parse("P1Y2M3D"));
      assertEquals(timesJson.getMethod("toJSON", times).invoke(null, obj), json);
    }
  }

  @Test
  public void invalidDateThrowsJSONProcessingException() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> timesJson = loader.loadClass("demo.internal.TimesJSON");
      try {
        timesJson.getMethod("fromJSON", String.class).invoke(null,
            "{\"instant\":\"NOPE\",\"localDate\":\"2026-05-16\","
            + "\"localDateTime\":\"2026-05-16T10:15:30\","
            + "\"offsetDateTime\":\"2026-05-16T10:15:30+01:00\","
            + "\"zonedDateTime\":\"2026-05-16T10:15:30+02:00[Europe/Paris]\","
            + "\"duration\":\"PT1H30M\",\"period\":\"P1Y2M3D\"}");
        fail("invalid Instant must throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException",
            "expected JSONProcessingException, got: " + e.getCause());
      }
    }
  }

  @Test
  public void nullJavaTimeOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) extras.loader()) {
      Class<?> times  = loader.loadClass("demo.Times");
      Class<?> timesJson = loader.loadClass("demo.internal.TimesJSON");
      Object obj = times.getConstructor(
          java.time.Instant.class, java.time.LocalDate.class, java.time.LocalDateTime.class,
          java.time.OffsetDateTime.class, java.time.ZonedDateTime.class,
          java.time.Duration.class, java.time.Period.class)
          .newInstance(java.time.Instant.parse("2026-05-16T10:15:30Z"),
              null, null, null, null, null, null);
      String json = (String) timesJson.getMethod("toJSON", times).invoke(null, obj);
      assertEquals(json, "{\"instant\":\"2026-05-16T10:15:30Z\"}",
          "null java.time fields must be omitted, not serialized as \"null\"");
    }
  }
}
