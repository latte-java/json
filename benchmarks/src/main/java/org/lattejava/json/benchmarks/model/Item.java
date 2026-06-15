/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * Element record for the large scenario's 1,000-item list.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Item(long id, String sku, String name, int quantity, BigDecimal price, boolean active) {
}
