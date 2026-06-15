/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import org.lattejava.json.JSON;

/**
 * The deep scenario root: a recursive record nested to depth 14, just under the library's default
 * nesting cap of 16.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Node(String name, int depth, Node child) {
}
