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
 * Jackson databind benchmarks: shared ObjectMapper from {@link Libraries}, byte[] in and byte[] out.
 *
 * @author Brian Pontarelli
 */
@BenchmarkMode(Mode.Throughput)
@Fork(2)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
public class JacksonBenchmark {
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
  public Account api_deserialize() throws Exception {
    return Libraries.JACKSON.readValue(apiFixture, Account.class);
  }

  @Benchmark
  public byte[] api_serialize() throws Exception {
    return Libraries.JACKSON.writeValueAsBytes(apiValue);
  }

  @Benchmark
  public Node deep_deserialize() throws Exception {
    return Libraries.JACKSON.readValue(deepFixture, Node.class);
  }

  @Benchmark
  public byte[] deep_serialize() throws Exception {
    return Libraries.JACKSON.writeValueAsBytes(deepValue);
  }

  @Benchmark
  public JWTClaims jwt_deserialize() throws Exception {
    return Libraries.JACKSON.readValue(jwtFixture, JWTClaims.class);
  }

  @Benchmark
  public byte[] jwt_serialize() throws Exception {
    return Libraries.JACKSON.writeValueAsBytes(jwtValue);
  }

  @Benchmark
  public Batch large_deserialize() throws Exception {
    return Libraries.JACKSON.readValue(largeFixture, Batch.class);
  }

  @Benchmark
  public byte[] large_serialize() throws Exception {
    return Libraries.JACKSON.writeValueAsBytes(largeValue);
  }

  @Benchmark
  public MetricSeries numbers_deserialize() throws Exception {
    return Libraries.JACKSON.readValue(numbersFixture, MetricSeries.class);
  }

  @Benchmark
  public byte[] numbers_serialize() throws Exception {
    return Libraries.JACKSON.writeValueAsBytes(numbersValue);
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
  public Document strings_deserialize() throws Exception {
    return Libraries.JACKSON.readValue(stringsFixture, Document.class);
  }

  @Benchmark
  public byte[] strings_serialize() throws Exception {
    return Libraries.JACKSON.writeValueAsBytes(stringsValue);
  }
}
