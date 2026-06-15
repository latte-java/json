/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * Mid-level object for the api scenario: UUID, Instant, enum, BigDecimal, and a nested list.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Order(UUID id, Instant placedAt, OrderStatus status, BigDecimal total, List<LineItem> items) {
}
