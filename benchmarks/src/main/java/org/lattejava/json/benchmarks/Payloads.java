/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks;

import module java.base;

import org.lattejava.json.benchmarks.model.Account;
import org.lattejava.json.benchmarks.model.AccountType;
import org.lattejava.json.benchmarks.model.Address;
import org.lattejava.json.benchmarks.model.Batch;
import org.lattejava.json.benchmarks.model.Document;
import org.lattejava.json.benchmarks.model.Item;
import org.lattejava.json.benchmarks.model.JWTClaims;
import org.lattejava.json.benchmarks.model.LineItem;
import org.lattejava.json.benchmarks.model.MetricSeries;
import org.lattejava.json.benchmarks.model.Node;
import org.lattejava.json.benchmarks.model.Order;
import org.lattejava.json.benchmarks.model.OrderStatus;
import org.lattejava.json.benchmarks.model.Sample;

/**
 * Deterministic object graphs and checked-in fixture bytes for every scenario. No clocks, no randomness —
 * the graphs must be byte-for-byte reproducible so the committed fixtures stay in sync.
 *
 * @author Brian Pontarelli
 */
public final class Payloads {
  private Payloads() {
  }

  public static Account api() {
    List<Order> orders = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      List<LineItem> items = List.of(
          new LineItem("SKU-" + (1000 + i * 2), "Ergonomic aluminum widget with reinforced mounting bracket", 2 + i,
              new BigDecimal("24.95")),
          new LineItem("SKU-" + (1001 + i * 2), "Replacement filter cartridge, dual-stage, universal fit", 1,
              new BigDecimal("129.50")));
      orders.add(new Order(UUID.fromString("00000000-0000-4000-8000-00000000000" + (i + 1)),
          Instant.parse("2026-0" + (i + 3) + "-15T10:30:00Z"), OrderStatus.values()[i],
          new BigDecimal("179.40"), items));
    }
    return new Account(UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479"), "Adelaide Brightwater",
        "adelaide.brightwater@example.com", AccountType.PRO, Instant.parse("2024-11-02T08:15:30Z"),
        Instant.parse("2026-06-01T17:45:12Z"),
        new Address("742 Evergreen Terrace", "Suite 210", "Springfield", "OR", "97477", "US"),
        orders, true, 17);
  }

  public static Node deep() {
    Node node = new Node("deep-nesting-scenario-level-14", 14, null);
    for (int depth = 13; depth >= 1; depth--) {
      node = new Node("deep-nesting-scenario-level-" + depth, depth, node);
    }
    return node;
  }

  public static byte[] fixture(String name) {
    try (InputStream in = Payloads.class.getResourceAsStream("/payloads/" + name + ".json")) {
      if (in == null) {
        throw new IllegalStateException("Missing fixture [" + name + "]");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static JWTClaims jwt() {
    return new JWTClaims("https://idp.example.com", "f47ac10b-58cc-4372-a567-0e02b2c3d479", "api://default",
        1781234567L, 1781230967L, "9a7b6c5d-4e3f-4a2b-9c8d-7e6f5a4b3c2d", List.of("admin", "editor", "viewer"));
  }

  public static Batch large() {
    List<Item> items = new ArrayList<>(1000);
    for (int i = 0; i < 1000; i++) {
      items.add(new Item(1_000_000L + i, String.format("SKU-%05d", i), "Warehouse item number " + i,
          i % 10 + 1, new BigDecimal(String.format("%d.%02d", (i * 37) % 1000, i % 100)), i % 7 != 0));
    }
    return new Batch("batch-2026-06-12-001", Instant.parse("2026-06-12T00:00:00Z"), items);
  }

  public static MetricSeries numbers() {
    List<Sample> samples = new ArrayList<>(150);
    for (int i = 0; i < 150; i++) {
      samples.add(new Sample(1_700_000_000_000L + i * 60_000L, (i * 977L) % 100_000L, (i % 997) / 997.0,
          new BigDecimal(String.format("%d.%04d", (i * 13) % 100_000, (i * 7919) % 10_000))));
    }
    return new MetricSeries("requests.latency.p99", "microseconds", samples);
  }

  public static Document strings() {
    String[] base = {
        "The \"quoted\" word problem: every serializer must escape \\ backslashes \\ and \"double quotes\" correctly, plus\ttabs and\nnewlines embedded mid-paragraph.",
        "Unicode beyond ASCII: café, naïve, über, façade, smörgåsbord — accented Latin is the easy half of the problem.",
        "CJK text stresses multi-byte UTF-8 encoding: 性能テストは重要です。序列化速度を測定します。",
        "Emoji are surrogate pairs in UTF-16 and four bytes in UTF-8: 🚀 💡 🎉 ✨ — they punish naive char-by-char escapers.",
        "Control characters like \u0001 and \u001F must be \\u-escaped per RFC 8259, while / may pass through unescaped.",
        "Mixed content with <html> & ampersands = the characters Gson HTML-escapes by default; semantic equality must survive that.",
        "Greek and Cyrillic round out two-byte UTF-8: αβγδ абвг שלום — plus right-to-left Hebrew for good measure.",
        "A long plain-ASCII paragraph to balance the mix, because most real-world JSON strings are boring identifiers, sentences, and URLs like https://example.com/v1/resources?page=2."
    };
    List<String> paragraphs = new ArrayList<>(base.length * 9);
    for (int i = 0; i < 9; i++) {
      for (String p : base) {
        paragraphs.add("(" + i + ") " + p);
      }
    }
    return new Document("Escape & Unicode Torture Test 🧪", "The Latte Project", "mul", paragraphs);
  }
}
