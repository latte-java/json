/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * The numbers scenario root.
 *
 * @author Brian Pontarelli
 */
@JSON
public record MetricSeries(String name, String unit, List<Sample> samples) {
}
