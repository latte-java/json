/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import org.lattejava.json.JSON;

/**
 * Nested object for the api scenario.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Address(String line1, String line2, String city, String state, String postalCode, String country) {
}
