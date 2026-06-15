/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks;

import module java.base;

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

/**
 * Correctness pre-flight for the benchmarks: every library must (a) deserialize each checked-in fixture to
 * the expected object, (b) round-trip its own output, and (c) round-trip every other library's output —
 * compared with record equality, so key order and number formatting are free to differ. Prints one
 * {@code PASS <lib>} or {@code FAIL <lib>} line per library and exits non-zero on any failure. Usage:
 * {@code Verify [lib,lib,...]} (default: all).
 *
 * @author Brian Pontarelli
 */
public final class Verify {
  private static final List<String> ALL = List.of("latte", "jackson", "gson");

  private static final Set<String> failed = new TreeSet<>();

  private static final List<String> problems = new ArrayList<>();

  private static final Set<String> selfFailed = new TreeSet<>();

  private Verify() {
  }

  public static void main(String[] args) {
    Set<String> enabled = args.length == 0 ? Set.copyOf(ALL) : Set.of(args[0].split(","));

    scenario("api", Payloads.api(), enabled, Account.class, AccountJSON::toJSONBytes, AccountJSON::fromJSON);
    scenario("deep", Payloads.deep(), enabled, Node.class, NodeJSON::toJSONBytes, NodeJSON::fromJSON);
    scenario("jwt", Payloads.jwt(), enabled, JWTClaims.class, JWTClaimsJSON::toJSONBytes, JWTClaimsJSON::fromJSON);
    scenario("large", Payloads.large(), enabled, Batch.class, BatchJSON::toJSONBytes, BatchJSON::fromJSON);
    scenario("numbers", Payloads.numbers(), enabled, MetricSeries.class, MetricSeriesJSON::toJSONBytes,
        MetricSeriesJSON::fromJSON);
    scenario("strings", Payloads.strings(), enabled, Document.class, DocumentJSON::toJSONBytes,
        DocumentJSON::fromJSON);

    problems.forEach(System.err::println);
    for (String lib : ALL) {
      if (enabled.contains(lib)) {
        System.out.println((failed.contains(lib) ? "FAIL " : "PASS ") + lib);
      }
    }
    System.exit(failed.isEmpty() ? 0 : 1);
  }

  private static <T> boolean check(String scenario, String label, T expected, Callable<T> actual) {
    try {
      T value = actual.call();
      if (expected.equals(value)) {
        return true;
      }
      problems.add("Scenario [" + scenario + "] check [" + label + "]: result does not equal the expected object");
      return false;
    } catch (Exception e) {
      problems.add("Scenario [" + scenario + "] check [" + label + "]: threw [" + e + "]");
      return false;
    }
  }

  private static <T> void scenario(String name, T expected, Set<String> enabled, Class<T> type,
                                   Serializer<T> latteSerializer, Deserializer<T> latteDeserializer) {
    List<Lib<T>> libs = new ArrayList<>();
    if (enabled.contains("latte")) {
      libs.add(new Lib<>("latte", latteSerializer, latteDeserializer));
    }
    if (enabled.contains("jackson")) {
      libs.add(new Lib<>("jackson", Libraries.JACKSON::writeValueAsBytes, b -> Libraries.JACKSON.readValue(b, type)));
    }
    if (enabled.contains("gson")) {
      libs.add(new Lib<>("gson", v -> Libraries.GSON.toJson(v).getBytes(StandardCharsets.UTF_8),
          b -> Libraries.GSON.fromJson(new String(b, StandardCharsets.UTF_8), type)));
    }

    byte[] fixture = Payloads.fixture(name);
    for (Lib<T> lib : libs) {
      boolean fixtureOK = check(name, lib.name + " fixture-deserialize", expected,
          () -> lib.deserializer.apply(fixture));
      boolean roundTripOK = check(name, lib.name + " self round-trip", expected,
          () -> lib.deserializer.apply(lib.serializer.apply(expected)));
      if (!fixtureOK || !roundTripOK) {
        selfFailed.add(lib.name);
        failed.add(lib.name);
      }
    }

    for (Lib<T> serializer : libs) {
      for (Lib<T> deserializer : libs) {
        if (serializer != deserializer && !check(name, serializer.name + "->" + deserializer.name, expected,
            () -> deserializer.deserializer.apply(serializer.serializer.apply(expected)))) {
          // Blame a library already known broken on its own; otherwise both.
          if (selfFailed.contains(serializer.name) == selfFailed.contains(deserializer.name)) {
            failed.add(serializer.name);
            failed.add(deserializer.name);
          } else {
            failed.add(selfFailed.contains(serializer.name) ? serializer.name : deserializer.name);
          }
        }
      }
    }
  }

  @FunctionalInterface
  private interface Deserializer<T> {
    T apply(byte[] json) throws Exception;
  }

  @FunctionalInterface
  private interface Serializer<T> {
    byte[] apply(T value) throws Exception;
  }

  private record Lib<T>(String name, Serializer<T> serializer, Deserializer<T> deserializer) {
  }
}
