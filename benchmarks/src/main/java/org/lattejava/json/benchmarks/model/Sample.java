/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * Element record for the numbers scenario: long, double, and BigDecimal parsing/formatting.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Sample(long timestamp, long count, double ratio, BigDecimal value) {
}
