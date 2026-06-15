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
import org.lattejava.json.benchmarks.model.internal.AccountJSON;
import org.lattejava.json.benchmarks.model.internal.BatchJSON;
import org.lattejava.json.benchmarks.model.internal.DocumentJSON;
import org.lattejava.json.benchmarks.model.internal.JWTClaimsJSON;
import org.lattejava.json.benchmarks.model.internal.MetricSeriesJSON;
import org.lattejava.json.benchmarks.model.internal.NodeJSON;
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
 * lattejava-json benchmarks: the generated {@code *JSON} companions, byte[] in and byte[] out.
 *
 * <p>The leading {@code A} is deliberate: JMH runs benchmarks in lexicographic order of their fully
 * qualified name, so {@code ALatteBenchmark} sorts ahead of {@code GsonBenchmark}/{@code JacksonBenchmark}
 * and measures first, while the CPU is coolest (relevant on fanless hardware that thermal-throttles under
 * sustained load). Per-benchmark JMH warmup still applies to every library equally.
 *
 * @author Brian Pontarelli
 */
@BenchmarkMode(Mode.Throughput)
@Fork(2)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
public class ALatteBenchmark {
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
    return AccountJSON.fromJSON(apiFixture);
  }

  @Benchmark
  public byte[] api_serialize() {
    return AccountJSON.toJSONBytes(apiValue);
  }

  @Benchmark
  public Node deep_deserialize() {
    return NodeJSON.fromJSON(deepFixture);
  }

  @Benchmark
  public byte[] deep_serialize() {
    return NodeJSON.toJSONBytes(deepValue);
  }

  @Benchmark
  public JWTClaims jwt_deserialize() {
    return JWTClaimsJSON.fromJSON(jwtFixture);
  }

  @Benchmark
  public byte[] jwt_serialize() {
    return JWTClaimsJSON.toJSONBytes(jwtValue);
  }

  @Benchmark
  public Batch large_deserialize() {
    return BatchJSON.fromJSON(largeFixture);
  }

  @Benchmark
  public byte[] large_serialize() {
    return BatchJSON.toJSONBytes(largeValue);
  }

  @Benchmark
  public MetricSeries numbers_deserialize() {
    return MetricSeriesJSON.fromJSON(numbersFixture);
  }

  @Benchmark
  public byte[] numbers_serialize() {
    return MetricSeriesJSON.toJSONBytes(numbersValue);
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
    return DocumentJSON.fromJSON(stringsFixture);
  }

  @Benchmark
  public byte[] strings_serialize() {
    return DocumentJSON.toJSONBytes(stringsValue);
  }
}
