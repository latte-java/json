/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks;

import module java.base;

import org.lattejava.json.benchmarks.model.internal.AccountJSON;
import org.lattejava.json.benchmarks.model.internal.BatchJSON;
import org.lattejava.json.benchmarks.model.internal.DocumentJSON;
import org.lattejava.json.benchmarks.model.internal.JWTClaimsJSON;
import org.lattejava.json.benchmarks.model.internal.MetricSeriesJSON;
import org.lattejava.json.benchmarks.model.internal.NodeJSON;

/**
 * Dev-time tool: writes the checked-in fixture payloads. Usage: {@code GenerateFixtures <output-dir>}.
 * Rerun (and re-commit the outputs) whenever {@link Payloads} changes.
 *
 * @author Brian Pontarelli
 */
public final class GenerateFixtures {
  private GenerateFixtures() {
  }

  public static void main(String[] args) throws IOException {
    Path dir = Path.of(args[0]);
    Files.createDirectories(dir);
    Files.write(dir.resolve("api.json"), AccountJSON.toJSONBytes(Payloads.api()));
    Files.write(dir.resolve("deep.json"), NodeJSON.toJSONBytes(Payloads.deep()));
    Files.write(dir.resolve("jwt.json"), JWTClaimsJSON.toJSONBytes(Payloads.jwt()));
    Files.write(dir.resolve("large.json"), BatchJSON.toJSONBytes(Payloads.large()));
    Files.write(dir.resolve("numbers.json"), MetricSeriesJSON.toJSONBytes(Payloads.numbers()));
    Files.write(dir.resolve("strings.json"), DocumentJSON.toJSONBytes(Payloads.strings()));
    System.out.println("Fixtures written to [" + dir + "]");
  }
}
