/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * The large scenario root: ~100 KB of list-heavy JSON.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Batch(String batchId, Instant createdAt, List<Item> items) {
}
