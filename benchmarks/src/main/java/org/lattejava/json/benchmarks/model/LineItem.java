/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * Leaf object for the api scenario's order lists.
 *
 * @author Brian Pontarelli
 */
@JSON
public record LineItem(String sku, String description, int quantity, BigDecimal unitPrice) {
}
