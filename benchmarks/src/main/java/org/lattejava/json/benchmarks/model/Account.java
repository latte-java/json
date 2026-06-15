/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * The api scenario root: a typical ~1.5 KB API resource with mixed types and nesting.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Account(UUID id, String name, String email, AccountType type, Instant createdAt, Instant modifiedAt,
                      Address billing, List<Order> orders, boolean active, long version) {
}
