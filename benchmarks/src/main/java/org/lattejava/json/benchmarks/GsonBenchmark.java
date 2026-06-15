/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks;

import module java.base;

import java.util.concurrent.TimeUnit;
import org.lattejava.json.benchmarks.model.Account;
import org.lattejava.json.benchmarks.model.Batch;
import org.lattejava.json.benchmarks.model.Document;
import org.lattejava.json.benchmarks.model.JWTClaims;
import org.lattejava.json.benchmarks.model.MetricSeries;
import org.lattejava.json.benchmarks.model.Node;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Gson benchmarks: shared Gson from {@link Libraries}. Gson's API is String-based, so the byte[] boundary
 * includes the UTF-8 conversion — that is its genuine cost when inputs and outputs are bytes.
 *
 * @author Brian Pontarelli
 */
@BenchmarkMode(Mode.Throughput)
@Fork(2)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
public class GsonBenchmark {
  private byte[] apiFixture;
  private Account apiValue;
  private byte[] deepFixture;
  private Node deepValue;
  private byte[] jwtFixture;
  private JWTClaims jwtValue;
  private byte[] largeFixture;
  private Batch largeValue;
  private byte[] numbersFixture;
  private MetricSeries numbersValue;
  private byte[] stringsFixture;
  private Document stringsValue;

  @Benchmark
  public Account api_deserialize() {
    return Libraries.GSON.fromJson(new String(apiFixture, StandardCharsets.UTF_8), Account.class);
  }

  @Benchmark
  public byte[] api_serialize() {
    return Libraries.GSON.toJson(apiValue).getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public Node deep_deserialize() {
    return Libraries.GSON.fromJson(new String(deepFixture, StandardCharsets.UTF_8), Node.class);
  }

  @Benchmark
  public byte[] deep_serialize() {
    return Libraries.GSON.toJson(deepValue).getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public JWTClaims jwt_deserialize() {
    return Libraries.GSON.fromJson(new String(jwtFixture, StandardCharsets.UTF_8), JWTClaims.class);
  }

  @Benchmark
  public byte[] jwt_serialize() {
    return Libraries.GSON.toJson(jwtValue).getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public Batch large_deserialize() {
    return Libraries.GSON.fromJson(new String(largeFixture, StandardCharsets.UTF_8), Batch.class);
  }

  @Benchmark
  public byte[] large_serialize() {
    return Libraries.GSON.toJson(largeValue).getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public MetricSeries numbers_deserialize() {
    return Libraries.GSON.fromJson(new String(numbersFixture, StandardCharsets.UTF_8), MetricSeries.class);
  }

  @Benchmark
  public byte[] numbers_serialize() {
    return Libraries.GSON.toJson(numbersValue).getBytes(StandardCharsets.UTF_8);
  }

  @Setup
  public void setup() {
    apiFixture = Payloads.fixture("api");
    apiValue = Payloads.api();
    deepFixture = Payloads.fixture("deep");
    deepValue = Payloads.deep();
    jwtFixture = Payloads.fixture("jwt");
    jwtValue = Payloads.jwt();
    largeFixture = Payloads.fixture("large");
    largeValue = Payloads.large();
    numbersFixture = Payloads.fixture("numbers");
    numbersValue = Payloads.numbers();
    stringsFixture = Payloads.fixture("strings");
    stringsValue = Payloads.strings();
  }

  @Benchmark
  public Document strings_deserialize() {
    return Libraries.GSON.fromJson(new String(stringsFixture, StandardCharsets.UTF_8), Document.class);
  }

  @Benchmark
  public byte[] strings_serialize() {
    return Libraries.GSON.toJson(stringsValue).getBytes(StandardCharsets.UTF_8);
  }
}
