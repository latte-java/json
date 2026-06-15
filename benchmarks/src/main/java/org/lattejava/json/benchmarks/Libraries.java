/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks;

import module java.base;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * The exact library configurations under test, set up the way a production user would configure them.
 * Verify and the JMH benchmarks share these instances so the correctness pre-flight checks precisely what
 * the benchmarks measure.
 *
 * @author Brian Pontarelli
 */
public final class Libraries {
  public static final Gson GSON = new GsonBuilder()
      .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
      .create();

  public static final ObjectMapper JACKSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private Libraries() {
  }

  /**
   * Gson has no built-in java.time support; this adapter uses ISO-8601 strings to match Jackson and
   * lattejava-json.
   */
  private static final class InstantAdapter extends TypeAdapter<Instant> {
    @Override
    public Instant read(JsonReader in) throws IOException {
      return Instant.parse(in.nextString());
    }

    @Override
    public void write(JsonWriter out, Instant value) throws IOException {
      out.value(value.toString());
    }
  }
}
